import { BACKEND_API_URL } from "../config/backend";
import { CESIUM_ACCESS_TOKEN } from "../config/CesiumToken";
import * as Cesium from "cesium";

class DemoMapService {
	private _theViewer: Cesium.Viewer | null = null;
	// Track layers using Cesium's ImageryLayer type
	private readonly _layers: Cesium.ImageryLayer[] = [];
	private readonly _dataSources: Cesium.DataSource[] = [];
	private _isInitializing: boolean = false;

	constructor() {}

	public init(viewerElement: HTMLDivElement): void {
		if (this._isInitializing || this._theViewer) return;
		this._isInitializing = true;

		Cesium.Ion.defaultAccessToken = CESIUM_ACCESS_TOKEN;

		this._theViewer = new Cesium.Viewer(viewerElement, {
			baseLayerPicker: true,
			// Allows the 2D map view to break away from absolute North-up constraints
			mapMode2D: Cesium.MapMode2D.ROTATE,
		});

		this._configureWWStyleControls();

		// Install populated custom layers into Cesium's imagery collection
		for (const layer of this._layers) {
			this._theViewer.imageryLayers.add(layer);
		}
		for (const dataSource of this._dataSources) {
			this._theViewer.dataSources.add(dataSource);
		}
		this._isInitializing = false;
	}

	public addLayer(layer: Cesium.ImageryLayer): () => void {
		this._layers.push(layer);

		if (this._theViewer) {
			this._theViewer.imageryLayers.add(layer);
		}

		// Return a cleanup/unmount function
		return () => {
			const index = this._layers.indexOf(layer);
			if (index >= 0) {
				this._layers.splice(index, 1);
			}
			if (this._theViewer) {
				this._theViewer.imageryLayers.remove(layer);
			}
		};
	}

	public addDataSource(dataSource: Cesium.DataSource): () => void {
		this._dataSources.push(dataSource);

		// If the viewer is already active, inject it immediately
		if (this._theViewer) {
			this._theViewer.dataSources.add(dataSource);
		}

		// Return a clean teardown function
		return () => {
			const index = this._dataSources.indexOf(dataSource);
			if (index >= 0) {
				this._dataSources.splice(index, 1);
			}
			if (this._theViewer) {
				this._theViewer.dataSources.remove(dataSource, true);
			}
		};
	}
	public goTo(latitude: number, longitude: number, altitude: number): void {
		if (this._theViewer) {
			// Cesium uses radians for Cartographic positions
			this._theViewer.camera.flyTo({
				destination: Cesium.Cartographic.toCartesian(
					Cesium.Cartographic.fromDegrees(longitude, latitude, altitude),
				),
				duration: 2.0, // Smooth animation duration in seconds
			});
		}
	}

	public redraw(): void {
		// Cesium renders automatically on the requestAnimationFrame loop.
		// If you are in requestRenderMode, this forces a single frame render.
		this._theViewer?.scene.requestRender();
	}

	public destroy(): void {
		if (this._theViewer) {
			this._theViewer.imageryLayers.removeAll(true);
			this._theViewer.destroy();
			this._theViewer = null;
		}
	}

	private getScreenCenterPosition(): Cesium.Cartesian3 | undefined {
		const canvas = this._theViewer.canvas;
		const centerWindowPos = new Cesium.Cartesian2(canvas.clientWidth / 2, canvas.clientHeight / 2);
		const ray = this._theViewer.camera.getPickRay(centerWindowPos);

		if (!ray) return undefined;

		// Pick the globe surface (handles terrain seamlessly if enabled)
		return this._theViewer.scene.globe.pick(ray, this._theViewer.scene);
	}

	private _configureWWStyleControls() {
		const scene = this._theViewer.scene;
		const camera = this._theViewer.camera;
		const controller = scene.screenSpaceCameraController;

		// 1. Disable default Cesium inputs that conflict
		controller.enableRotate = false;
		controller.enableTilt = false;
		controller.enableLook = false;
		controller.enableTranslate = false;
		// Cesium's default mouse wheel zoom zooms in or out on the point hovered by the mouse,
		// which is exactly what I want.
		controller.enableZoom = true;

		// 2. Setup custom event handler
		const handler = new Cesium.ScreenSpaceEventHandler(scene.canvas);
		let isPanning = false;
		let isTilting = false;
		let panStart = null;

		let tiltCenterTransform: Cesium.Matrix4 = null;
		let tiltStartX = 0;
		let tiltStartY = 0;

		// Helper to get ground intersection point
		const getTargetPoint = position => {
			const ray = camera.getPickRay(position);
			return scene.globe
				? scene.globe.pick(ray, scene)
				: camera.pickEllipsoid(position, scene.ellipsoid || Cesium.Ellipsoid.WGS84);
		};

		// --- PANNING: Left Click & Drag ---
		handler.setInputAction(movement => {
			const intersection = getTargetPoint(movement.position);

			if (Cesium.defined(intersection)) {
				isPanning = true;
				panStart = Cesium.Cartesian3.clone(intersection);
				// Temporarily lock camera controls to block drift
				controller.enableInputs = false;
			}
		}, Cesium.ScreenSpaceEventType.LEFT_DOWN);

		handler.setInputAction(() => {
			isPanning = false;
			panStart = null;
			controller.enableInputs = true;
		}, Cesium.ScreenSpaceEventType.LEFT_UP);

		// --- TILT & ROTATE: Right Click & Drag ---
		handler.setInputAction(click => {
			const tiltCenter = this.getScreenCenterPosition();
			if (tiltCenter) {
				tiltCenterTransform = Cesium.Transforms.eastNorthUpToFixedFrame(tiltCenter);
				isTilting = true;
				tiltStartX = click.position.x;
				tiltStartY = click.position.y;
				controller.enableInputs = false;
			}
		}, Cesium.ScreenSpaceEventType.RIGHT_DOWN);

		handler.setInputAction(movement => {
			if (isPanning && panStart) {
				// Raycast the continuous current mouse position
				const ray = camera.getPickRay(movement.endPosition);
				const currentWorldPoint = scene.globe
					? scene.globe.pick(ray, scene)
					: scene.camera.pickEllipsoid(movement.endPosition, scene.ellipsoid);

				if (Cesium.defined(currentWorldPoint)) {
					// Calculate the physical translation offset vector in world coordinates
					const offset = Cesium.Cartesian3.subtract(panStart, currentWorldPoint, new Cesium.Cartesian3());

					// Translate the camera's position smoothly by the physical delta
					Cesium.Cartesian3.add(camera.position, offset, camera.position);
				}
			} else if (isTilting) {
				const sensitivity = 0.005;

				// 1. Find the canvas center coordinates
				const centerY = scene.canvas.clientHeight / 2;
				const screenH = scene.canvas.clientHeight;

				const movePos = movement.endPosition;

				const twoD = scene.mode == Cesium.SceneMode.SCENE2D;

				if (!twoD) {
					// Temporarily move the camera to the point on the ground at the center of the screen,
					// so tilt and rotation happens around this point.
					camera.lookAtTransform(tiltCenterTransform);

					//Handle tilt.  Upward mouse movement tilts view up.  Downward tilts down.
					if (movePos.y != tiltStartY) {
						if (movePos.y < tiltStartY) camera.rotateDown((tiltStartY - movePos.y) * sensitivity);
						else if (movePos.y > tiltStartY) camera.rotateUp((movePos.y - tiltStartY) * sensitivity);
					}
				}

				// Handle rotation.
				// Rotate in the direction of horizontal mouse movement if the mouse is outide the middle 20% of the screen.
				if (movePos.x != tiltStartX) {
					let rotate = true;
					let rotAngle = (movePos.x - tiltStartX) * sensitivity;
					if (tiltStartY < centerY - screenH / 10) {
						//Rotation angle is correct
					} else if (tiltStartY > centerY + screenH / 10) {
						rotAngle = -rotAngle;
					} else {
						rotate = false;
					}
					if (!rotate) {
						// Do nothing
					} else if (rotAngle > 0) {
						if(twoD)
							camera.twistLeft(rotAngle);
						else
							camera.rotateRight(rotAngle);
					} else {
						if(twoD)
							camera.twistRight(-rotAngle);
						else
							camera.rotateLeft(-rotAngle);
					}
				}

				if (!twoD) {
					//Reset the camera back to the global coordinate frame
					camera.lookAtTransform(Cesium.Matrix4.IDENTITY);
				}
				//Move the start point so tilt/rotate is stateless
				tiltStartX = movePos.x;
				tiltStartY = movePos.y;
			}
		}, Cesium.ScreenSpaceEventType.MOUSE_MOVE);

		handler.setInputAction(() => {
			isTilting = false;
			controller.enableInputs = true;
		}, Cesium.ScreenSpaceEventType.RIGHT_UP);
	}
}

export default DemoMapService;
