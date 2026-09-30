import { Experimental_CssVarsProvider } from "@mui/material";
import { BACKEND_API_URL } from "../config/backend";
import { CESIUM_ACCESS_TOKEN } from "../config/CesiumToken";
import * as Cesium from "cesium";
import { clientData, lifeCycle } from "./services";

interface MapView{
	position: MapViewPosition;
	heading: number;
	pitch: number;
	roll: number;
}

interface MapViewPosition{
	x: number;
	y: number;
	z: number;
}

class DemoMapService {
	private _theViewer: Cesium.Viewer | null = null;
	// Track layers using Cesium's ImageryLayer type
	private readonly _layers: Cesium.ImageryLayer[] = [];
	private readonly _dataSources: MapShapeSet[] = [];
	private _isInitializing: boolean = false;
	private readonly _mapInteractionListeners: Cesium.ScreenSpaceEventHandler[] = [];
	private _pickedShape: MapShape | null = null;
	private _viewChanged = false;
	private _viewSaveIntervalId: ReturnType<typeof setInterval> | null = null;

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

		this._mapInteractionListeners.push(this._configureWWStyleControls());
		this._mapInteractionListeners.push(this._setupShapeInteractionListening());
		lifeCycle.onInit(()=>this.restoreMapView());
		this._viewSaveIntervalId=setInterval(()=>{
			if(this._viewChanged){
				this.saveMapView();
				this._viewChanged=false;
			}
		}, 1000);
		this._theViewer.camera.moveEnd.addEventListener(()=>this._viewChanged=true);

		// Install populated custom layers into Cesium's imagery collection
		for (const layer of this._layers) {
			this._theViewer.imageryLayers.add(layer);
		}
		for (const dataSource of this._dataSources) {
			this._theViewer.dataSources.add(dataSource.dataSource);
		}
		this._isInitializing = false;
	}

	public get earthRadius(){
		return this._theViewer.ellipsoid.maximumRadius;
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

	public addShapes(shapes: MapShapeSet) {
		shapes.removeFromMap();
		this._dataSources.push(shapes);

		// If the viewer is already active, inject it immediately
		if (this._theViewer) {
			this._theViewer.dataSources.add(shapes.dataSource);
		}

		shapes.remove = () => {
			const index = this._dataSources.indexOf(shapes);
			if (index >= 0) {
				this._dataSources.splice(index, 1);
			}
			if (this._theViewer) {
				this._theViewer.dataSources.remove(shapes.dataSource, true);
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
			for (const handler of this._mapInteractionListeners) handler.destroy();
			this._theViewer.imageryLayers.removeAll(true);
			clearInterval(this._viewSaveIntervalId);
			this._theViewer.destroy();
			this._theViewer = null;
		}
	}

	private restoreMapView(){
		const savedView=clientData.get("map/view") as MapView | null;
		if(savedView){
			this._theViewer.camera.setView({
				destination: new Cesium.Cartesian3(savedView.position.x, savedView.position.y, savedView.position.z),
				orientation:{
					heading: savedView.heading,
					pitch: savedView.pitch,
					roll: savedView.roll,
				}
			});
		}
	}

	private saveMapView(){
		const camera=this._theViewer.camera;
		const savedView={
			position: {
				x: camera.position.x,
				y: camera.position.y,
				z: camera.position.z
			},
			heading: camera.heading,
			pitch: camera.pitch,
			roll: camera.roll,
		};
		clientData.set("map/view", savedView);
	}

	private getScreenCenterPosition(): Cesium.Cartesian3 | undefined {
		const canvas = this._theViewer.canvas;
		const centerWindowPos = new Cesium.Cartesian2(canvas.clientWidth / 2, canvas.clientHeight / 2);
		const ray = this._theViewer.camera.getPickRay(centerWindowPos);

		if (!ray) return undefined;

		// Pick the globe surface (handles terrain seamlessly if enabled)
		return this._theViewer.scene.globe.pick(ray, this._theViewer.scene);
	}

	private _configureWWStyleControls(): Cesium.ScreenSpaceEventHandler {
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
						if (twoD) camera.twistLeft(rotAngle);
						else camera.rotateRight(rotAngle);
					} else {
						if (twoD) camera.twistRight(-rotAngle);
						else camera.rotateLeft(-rotAngle);
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

		return handler;
	}

	private _setupShapeInteractionListening(): Cesium.ScreenSpaceEventHandler {
		const scene = this._theViewer.scene;
		const handler = new Cesium.ScreenSpaceEventHandler(scene.canvas);
		handler.setInputAction(movement => {
			let pickedShape= this.getVistaShapeAt(movement.endPosition);
			const prevPicked=this._pickedShape;
			this._pickedShape=pickedShape;
			if(prevPicked!=pickedShape){
				if(prevPicked)
					prevPicked.mouseUnhovered();
				if(pickedShape)
					pickedShape.mouseHovered(new LazyMouseEvent(scene, movement.endPosition));
			}
		}, Cesium.ScreenSpaceEventType.MOUSE_MOVE);
		this._theViewer.canvas.addEventListener("mouseleave", ()=>{
			const prevPicked=this._pickedShape;
			this._pickedShape=null;
			if(prevPicked)
				prevPicked.mouseUnhovered();
		});
		const buttonEventTypes=[
			Cesium.ScreenSpaceEventType.LEFT_CLICK,
			Cesium.ScreenSpaceEventType.LEFT_DOUBLE_CLICK,
			Cesium.ScreenSpaceEventType.LEFT_DOWN,
			Cesium.ScreenSpaceEventType.LEFT_UP,
			Cesium.ScreenSpaceEventType.MIDDLE_CLICK,
			Cesium.ScreenSpaceEventType.MIDDLE_DOWN,
			Cesium.ScreenSpaceEventType.MIDDLE_UP,
			Cesium.ScreenSpaceEventType.RIGHT_CLICK,
			Cesium.ScreenSpaceEventType.RIGHT_DOWN,
			Cesium.ScreenSpaceEventType.RIGHT_UP,
		];
		for(const type of buttonEventTypes){
			handler.setInputAction((event)=>{
				const shape=this.getDemoShapeAt(event.position);
				if(shape)
					shape.mouseButtonEvent(type, new LazyMouseEvent(scene, event.position));
			}, type);
		}
		return handler;
	}

	private getDemoShapeAt(position: Cesium.Cartesian2): MapShape | null {
		const picked = this._theViewer.scene.pick(position);
		let pickedShape: MapShape | null = null;
		if (Cesium.defined(picked)//
			&& picked.id instanceof Cesium.Entity//
			&& (picked.id as Cesium.Entity).properties.hasProperty(DEMO_SHAPE_PROPERTY)) {
			pickedShape=(picked.id.properties[DEMO_SHAPE_PROPERTY] as Cesium.Property).getValue() as MapShape;
		}
		return pickedShape;
	}
}

export class MapShapeSet {
	private readonly _dataSource: Cesium.DataSource;
	private _remove: () => void;

	constructor() {
		this._dataSource = new Cesium.CustomDataSource();
	}

	get dataSource(): Cesium.DataSource {
		return this._dataSource;
	}

	set remove(remove: () => void) {
		this._remove = remove;
	}

	public get name(): string {
		return this._dataSource.name;
	}
	public set name(name: string) {
		this._dataSource.name = name;
	}

	public get visible(): boolean {
		return this._dataSource.show;
	}
	public set visible(visible: boolean) {
		this._dataSource.show = visible;
	}

	public addShape(shape: MapShape) {
		this._dataSource.entities.add(shape.getCesiumEntity());
	}
	public removeShape(shape: MapShape) {
		this._dataSource.entities.remove(shape.getCesiumEntity());
	}
	public removeAllShapes() {
		this._dataSource.entities.removeAll();
	}

	public removeFromMap() {
		if (this._remove) {
			this._remove();
			this._remove;
		}
	}
}

export class LatLon {
	readonly lat: number;
	readonly lon: number;

	constructor(lat: number, lon: number) {
		this.lat = lat;
		this.lon = lon;
	}
}

export class LatLonAlt extends LatLon {
	readonly alt: number;

	constructor(lat: number, lon: number, alt: number) {
		super(lat, lon);
		this.alt = alt;
	}

	static fromCarto(carto: Cesium.Cartographic): LatLonAlt {
		return new LatLonAlt(
			Cesium.Math.toDegrees(carto.latitude),
			Cesium.Math.toDegrees(carto.longitude),
			carto.height,
		);
	}
}

export interface MouseEvent{
	screenPosition: Cesium.Cartesian2;
	position: Cesium.Cartesian3;
	geoPosition: LatLonAlt;
}

class LazyMouseEvent implements MouseEvent{
	private readonly _scene: Cesium.Scene;
	private readonly _screenPos: Cesium.Cartesian2;
	private _position: Cesium.Cartesian3 | null = null;
	private _geoPosition: LatLonAlt | null = null;

	constructor(scene: Cesium.Scene, screenPos: Cesium.Cartesian2){
		this._scene=scene;
		this._screenPos=screenPos;
	}

	get screenPosition(): Cesium.Cartesian2{
		return this._screenPos;
	}

	get position(): Cesium.Cartesian3{
		if(!this._position)
			this._position=this._scene.pickPosition(this._screenPos);
		return this._position;
	}

	get geoPosition(): LatLonAlt{
		if(!this._geoPosition){
			const pos=this.position;
			if(!pos)
				return null;
			this._geoPosition=LatLonAlt.fromCarto(Cesium.Cartographic.fromCartesian(pos));
		}
		return this._geoPosition;
	}
}

export type MapMouseButtonListener = (
	type: Cesium.ScreenSpaceEventType,
	event: MouseEvent,
) => void;
export type MapHoverListener=(event: MouseEvent) => void;

interface MapHoverAndUnhoverListener{
	hover: MapHoverListener
	unhover?: ()=>void;
}

const DEMO_SHAPE_PROPERTY="demoShape";

export interface MapShapeConfig{
	visible?: boolean;
	label?: MapLabelConfig;
}

export interface MapLabelConfig{
	text?: string | (() => string);
	position?: Cesium.Cartesian3;
	geoPosition?: LatLonAlt;
	fillColor?: Cesium.Color | (()=>Cesium.Color);
	outlineColor?: Cesium.Color | (()=>Cesium.Color);
	style?: Cesium.LabelStyle;
	pixelOffset?: Cesium.Cartesian2;
	horizontalOrigin?: Cesium.HorizontalOrigin;
	verticalOrigin?: Cesium.VerticalOrigin;
	font?: string;
	visible?: boolean;
}

export abstract class MapShape {
	private readonly _entity: Cesium.Entity;
	private readonly _buttonListeners: MapMouseButtonListener[] = [];
	private readonly _hoverListeners: MapHoverAndUnhoverListener[] = [];
	private _isHovered: boolean = false;
	private _labelFillColorDefault: boolean = true;
	private _labelOutlineColorDefault: boolean = true;
	private _labelPositionDefault: boolean = true;

	constructor(config?: MapShapeConfig){
		this._entity=new Cesium.Entity();
		this._entity.properties = new Cesium.PropertyBag();
		this._entity.properties.addProperty(DEMO_SHAPE_PROPERTY, new Cesium.ConstantProperty(this));
		if(config && config.label)
			this.label(config.label);
	}

	getCesiumEntity(): Cesium.Entity{
		return this._entity;
	}

	public abstract get visible(): boolean;
	public abstract set visible(visible: boolean);

	public label(config: MapLabelConfig){
		if(!config){
			if(this._entity.label)
				this._entity.label.show=new Cesium.ConstantProperty(false);
		} else{
			let position: Cesium.Cartesian3 | null = null;
			if(config.geoPosition){
				this._labelPositionDefault=false;
				position=Cesium.Cartesian3.fromDegrees(config.geoPosition.lon, config.geoPosition.lat, config.geoPosition.alt);
			} else if(config.position){
				this._labelPositionDefault=false;
				position=config.position;
			} else if(!this._entity.position?.getValue()){
				this._labelPositionDefault=true;
				position=this.getDefaultLabelPosition();
			}
			if(position)
				this._entity.position=new Cesium.ConstantPositionProperty(position);
			if(!this._entity.label){
				const labelConfig: Cesium.LabelGraphics.ConstructorOptions={};
				if(!config.text)
					labelConfig.text="Label Text";
				else if(typeof config.text==="function")
					labelConfig.text=new Cesium.CallbackProperty(config.text as (()=>string), false);
				else
					labelConfig.text=config.text as string;
				labelConfig.style= config.style ?? Cesium.LabelStyle.FILL_AND_OUTLINE;
				labelConfig.pixelOffset= config.pixelOffset;
				labelConfig.horizontalOrigin= config.horizontalOrigin?? Cesium.HorizontalOrigin.LEFT;
				labelConfig.verticalOrigin= config.verticalOrigin?? Cesium.VerticalOrigin.BOTTOM;
				labelConfig.font= config.font?? "20px sans-serif"; //Default 30px font is too huge
				labelConfig.show= config.visible?? true;
				if(config.fillColor){
					this._labelFillColorDefault=false;
					if(typeof config.fillColor==="function")
						labelConfig.fillColor=new Cesium.CallbackProperty(config.fillColor as (()=>Cesium.Color), false);
					else
						labelConfig.fillColor=config.fillColor as Cesium.Color;
				} else if(!this._entity.label?.fillColor?.getValue()){
					this._labelFillColorDefault=true;
					labelConfig.fillColor=this.getDefaultLabelColor();
				}
				if(config.outlineColor){
					this._labelOutlineColorDefault=false;
					if(typeof config.outlineColor==="function")
						labelConfig.outlineColor=new Cesium.CallbackProperty(config.outlineColor as (()=>Cesium.Color), false);
					else
						labelConfig.outlineColor=config.outlineColor as Cesium.Color;
				} else if(!this._entity.label?.outlineColor?.getValue()){
					this._labelOutlineColorDefault=true;
					labelConfig.outlineColor=this.getDefaultLabelColor();
				}
				this._entity.label=new Cesium.LabelGraphics(labelConfig);
			} else{
				if(config.text){
					if(typeof config.text==="function")
						this._entity.label.text=new Cesium.CallbackProperty(config.text as (()=>string), false);
					else
						this._entity.label.text=new Cesium.ConstantProperty(config.text as string);
				}
				if(config.fillColor){
					if(typeof config.fillColor==="function")
						this._entity.label.fillColor=new Cesium.CallbackProperty(config.fillColor as ()=>Cesium.Color, false);
					else
						this._entity.label.fillColor=new Cesium.ConstantProperty(config.fillColor as Cesium.Color);
				}
				if(config.outlineColor){
					if(typeof config.outlineColor==="function")
						this._entity.label.outlineColor=new Cesium.CallbackProperty(config.outlineColor as ()=>Cesium.Color, false);
					else
						this._entity.label.outlineColor=new Cesium.ConstantProperty(config.outlineColor as Cesium.Color);
				}
				if(config.style)
					this._entity.label.style=new Cesium.ConstantProperty(config.style);
				if(config.pixelOffset)
					this._entity.label.pixelOffset=new Cesium.ConstantProperty(config.pixelOffset);
				if(config.horizontalOrigin)
					this._entity.label.horizontalOrigin=new Cesium.ConstantProperty(config.horizontalOrigin);
				if(config.verticalOrigin)
					this._entity.label.verticalOrigin=new Cesium.ConstantProperty(config.verticalOrigin);
				if(config.font)
					this._entity.label.font=new Cesium.ConstantProperty(config.font);
				this._entity.label.show=new Cesium.ConstantProperty(config.visible?? true);
			}
		}
	}

	protected positionChanged(){
		if(this._entity.label && this._labelPositionDefault){
			const position=this.getDefaultLabelPosition();
			if(position)
				this._entity.position=new Cesium.ConstantPositionProperty(position);
		}
	}

	protected abstract getDefaultLabelPosition(): Cesium.Cartesian3;

	protected colorChanged(){
		if(this._entity.label && (this._labelFillColorDefault || this._labelOutlineColorDefault)){
			const defaultColor=new Cesium.ConstantProperty(this.getDefaultLabelColor());
			if(this._labelFillColorDefault)
				this._entity.label.fillColor=defaultColor;
			if(this._labelOutlineColorDefault)
				this._entity.label.outlineColor=defaultColor;
		}
	}

	protected abstract getDefaultLabelColor(): Cesium.Color;

	public addMouseButtonListener(listener: MapMouseButtonListener): () => void {
		this._buttonListeners.push(listener);
		return () => {
			const index = this._buttonListeners.indexOf(listener);
			if (index >= 0) this._buttonListeners.splice(index, 1);
		};
	}
	public addHoverListener(hoverListener: MapHoverListener, unhoverListener?: ()=>void): () => void {
		const hoverAndUnhover: MapHoverAndUnhoverListener = {
			hover: hoverListener,//
			unhover: unhoverListener
		}
		this._hoverListeners.push(hoverAndUnhover);
		return () => {
			const index = this._hoverListeners.indexOf(hoverAndUnhover);
			if (index >= 0) this._buttonListeners.splice(index, 1);
		};
	}
	public get hovered(): boolean {
		return this._isHovered;
	}

	mouseButtonEvent(type: Cesium.ScreenSpaceEventType, event: MouseEvent) {
		for (const listener of this._buttonListeners) {
			try {
				listener(type, event);
			} catch (e) {
				console.log("Error in shape hover listener ", listener, " for shape ", this, e);
			}
		}
	}
	mouseHovered(event: MouseEvent) {
		this._isHovered=true;
		for (const listener of this._hoverListeners) {
			try {
				listener.hover(event);
			} catch (e) {
				console.log("Error in shape hover listener ", listener, " for shape ", this, e);
			}
		}
	}
	mouseUnhovered(){
		this._isHovered=false;
		for (const listener of this._hoverListeners) {
			try {
				if(listener.unhover)
					listener.unhover();
			} catch (e) {
				console.log("Error in shape hover listener ", listener, " for shape ", this, e);
			}
		}
	}
}

export interface MapMarkerConfig extends MapShapeConfig{
	position?: Cesium.Cartesian3;
	geoPosition?: LatLonAlt;
	pixelSize?: number | (()=>number);
	color?: Cesium.Color | (()=>Cesium.Color);
	outlineColor?: Cesium.Color | (()=>Cesium.Color);
	outlineWidth?: number | (()=>number);
}

export class MapMarker extends MapShape{
	constructor(config?: MapMarkerConfig){
		super(config);
		if(config?.position){
			if(config.geoPosition)
				throw new Error("Specify position or geoPosition, but not both");
			this.getCesiumEntity().position=new Cesium.ConstantPositionProperty(config.position);
		} else if(config?.geoPosition){
			this.getCesiumEntity().position=new Cesium.ConstantPositionProperty(Cesium.Cartesian3.fromDegrees(
				config.geoPosition.lon, config.geoPosition.lat, config.geoPosition.alt));
		}
		const options: Cesium.PointGraphics.ConstructorOptions = {};
		if(config.pixelSize!==null && config.pixelSize!==undefined){
			options.pixelSize=(typeof config.pixelSize === "function")
				? new Cesium.CallbackProperty(config.pixelSize as ()=>number, false)
				: new Cesium.ConstantProperty(config.pixelSize as number);
		}
		if(config.color){
			options.color=(typeof config.color === "function")
				? new Cesium.CallbackProperty(config.color as ()=>Cesium.Color, false)
				: new Cesium.ConstantProperty(config.color as Cesium.Color);
		}
		if(config.outlineColor){
			options.outlineColor=(typeof config.outlineColor === "function")
				? new Cesium.CallbackProperty(config.outlineColor as ()=>Cesium.Color, false)
				: new Cesium.ConstantProperty(config.outlineColor as Cesium.Color);
		}
		if(config.outlineWidth!==null && config.outlineWidth!==undefined){
			options.outlineWidth=(typeof config.outlineWidth === "function")
				? new Cesium.CallbackProperty(config.outlineWidth as ()=>number, false)
				: new Cesium.ConstantProperty(config.outlineWidth as number);
		}
		this.getCesiumEntity().point=new Cesium.PointGraphics(options);
	}

	public get position(): Cesium.Cartesian3{
		return this.getCesiumEntity().position.getValue();
	}
	public set position(position: Cesium.Cartesian3){
			this.getCesiumEntity().position=new Cesium.ConstantPositionProperty(position);
	}

	public get geoPosition(): LatLonAlt{
		return LatLonAlt.fromCarto(Cesium.Cartographic.fromCartesian(this.position));
	}
	public set geoPosition(position: LatLonAlt){
		this.position=Cesium.Cartesian3.fromDegrees(position.lon, position.lat, position.alt);
	}

	public get visible(): boolean {
		return this.getCesiumEntity().show;
	}
	public set visible(visible: boolean) {
		this.getCesiumEntity().show=visible;
	}

	public get color(): Cesium.Color{
		return this.getCesiumEntity().point.color.getValue();
	}
	public set color(color: Cesium.Color){
		this.getCesiumEntity().point.color=new Cesium.ConstantProperty(color);
	}

	public label(config: MapLabelConfig): void {
		if(config?.position)
			config.position=null; //Don't set the position to the hovered point
		super.label(config);
	}

	protected getDefaultLabelPosition(): Cesium.Cartesian3 {
		return this.position;
	}
	protected getDefaultLabelColor(): Cesium.Color {
		return this.color;
	}
}

export interface PolyLineConfig extends MapShapeConfig{
	positions?: LatLonAlt[];
	width?: number | (()=>number);
	color?: Cesium.Color | (()=>Cesium.Color);
	material?: Cesium.MaterialProperty;
	clampToGround?: boolean;
}

export class MapPolyLine extends MapShape {
	private readonly _polyLine: Cesium.PolylineGraphics;
	private _positions: Cesium.Cartesian3[] = [];

	constructor(config?: PolyLineConfig) {
		super(config);

		if (config.positions) {
			for (const position of config.positions)
				this._positions.push(Cesium.Cartesian3.fromDegrees(position.lon, position.lat, position.alt));
		}
		const lineConfig: Cesium.PolylineGraphics.ConstructorOptions = {};
		lineConfig.positions=new Cesium.CallbackProperty((time, result) => {
			const newResult=(result ?? []) as Cesium.Cartesian3[];
			newResult.length=self._positions.length;
			for(let i=0;i<self._positions.length;i++)
				newResult[i]=self._positions[i];
			return newResult;
		}, false);
		if(config.width !== null && config.width !== undefined){
			if(typeof config.width === "function")
				lineConfig.width=new Cesium.CallbackProperty(config.width as ()=>number, false);
			else
				lineConfig.width=new Cesium.ConstantProperty(config.width as number);
		} else
			lineConfig.width=2;
		if(config){
			if(config.color){
				if(config.material)
					throw new Error("Specify either color or material, but not both");
				if(typeof config.color === "function")
					lineConfig.material=new Cesium.ColorMaterialProperty(new Cesium.CallbackProperty(config.color as ()=>Cesium.Color, false));
				else
					lineConfig.material=new Cesium.ColorMaterialProperty(config.color as Cesium.Color);
			} else if(config.material)
				lineConfig.material=config.material;

		} else
			lineConfig.material=new Cesium.ColorMaterialProperty(Cesium.Color.BLACK);
		lineConfig.clampToGround=config?.clampToGround ?? false;
		lineConfig.show=config && config.visible != undefined ? config.visible : true

		const self=this;
		this._polyLine = new Cesium.PolylineGraphics(lineConfig);
		this.getCesiumEntity().polyline=this._polyLine;
	}

	public modify(config: PolyLineConfig) {
		if (config.positions) this.geoPositions = config.positions;
		if (config.width !== null && config.width !== undefined){
			if(typeof config.width === "function")
				this._polyLine.width=new Cesium.CallbackProperty(config.width as ()=>number, false);
			else
				this._polyLine.width=new Cesium.ConstantProperty(config.width as number);
		}
		if(config.material){
			if(config.color)
				throw new Error("Specify material or color, but not both");
			this._polyLine.material=config.material;
		}
		if (config.color){
			if(typeof config.color === "function")
				this._polyLine.material=new Cesium.ColorMaterialProperty(new Cesium.CallbackProperty(config.color as ()=>Cesium.Color, false));
			else
				this._polyLine.material=new Cesium.ColorMaterialProperty(config.color as Cesium.Color);
		}
		if (config.clampToGround !== undefined) this.clampToGround = config.clampToGround;
		if (config.visible !== undefined) this.visible = config.visible;
	}

	public get cartPositions(): readonly Cesium.Cartesian3[] {
		return this._positions;
	}

	public get geoPositions(): readonly LatLonAlt[] {
		return this._positions.map(cart => LatLonAlt.fromCarto(Cesium.Cartographic.fromCartesian(cart)));
	}

	public set geoPositions(geoPositions: LatLonAlt[]) {
		const newPositions: Cesium.Cartesian3 []=[];
		for (let p = 0; p < geoPositions.length; p++) {
			if (geoPositions[p]) {
				newPositions.push(Cesium.Cartesian3.fromDegrees(
					geoPositions[p].lon,
					geoPositions[p].lat,
					geoPositions[p].alt,
				));
			} else if(p<this._positions.length)
				newPositions.push(this._positions[p]);
			else
				throw new Error(
					"Sparse position configuration error: missing position at index " + this._positions.length,
				);
		}
		this._positions=newPositions;
		this.positionChanged();
	}

	public get material(): Cesium.MaterialProperty {
		return this._polyLine.material;
	}
	public set material(material: Cesium.MaterialProperty){
		this._polyLine.material=material;
	}
	public get color(): Cesium.Color | null {
		if(this.material instanceof Cesium.ColorMaterialProperty)
			return (this.material as Cesium.ColorMaterialProperty).color.getValue();
		else
			return null;
	}
	public set color(color: Cesium.Color){
		const mtrl=this.material as any;
		if(mtrl.color && typeof mtrl.color.setValue==="function")
			mtrl.color.setValue(color);
		else
			throw new Error("Cannot set the color of a polyline that was initialized with a non-color material");
		this.colorChanged();
	}

	public get width(): number {
		return this._polyLine.width.getValue();
	}
	public set width(width: number) {
		(this._polyLine.width as Cesium.ConstantProperty).setValue(width);
	}

	public get clampToGround(): boolean {
		return this._polyLine.clampToGround.getValue();
	}
	public set clampToGround(clamp: boolean) {
		(this._polyLine.clampToGround as Cesium.ConstantProperty).setValue(clamp);
	}

	public get visible(): boolean {
		return this._polyLine.show.getValue();
	}
	public set visible(visible: boolean) {
		(this._polyLine.show as Cesium.ConstantProperty).setValue(visible);
	}

	protected getDefaultLabelPosition(): Cesium.Cartesian3{
		switch(this._positions.length){
			case 0:
				return null;
			case 1:
				return this._positions[0];
			default:
				const p0=this._positions[0];
				const p1=this._positions[1];
				return new Cesium.Cartesian3((p0.x+p1.x)/2, (p0.y+p1.y)/2, (p0.z+p1.z)/2);
		}
	}

	protected getDefaultLabelColor(): Cesium.Color{
		return this.color;
	}
}

export default DemoMapService;
