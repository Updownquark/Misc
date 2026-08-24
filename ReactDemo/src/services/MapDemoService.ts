import { backend, lifeCycle, demoMap } from "./services";
import {
	Entity,
	Cartesian3,
	Color,
	PolylineGraphics,
	CallbackProperty,
	CustomDataSource,
	HeightReference,
	MaterialProperty,
} from "cesium";
import { MapPolyLine, MapShapeSet } from "./DemoMapService";

class MapDemoValues {
	private readonly _lat0: number;
	private readonly _lon0: number;
	private readonly _alt0: number;
	private readonly _lat1: number;
	private readonly _lon1: number;
	private readonly _alt1: number;

	public constructor(lat0: number, lon0: number, alt0: number, lat1: number, lon1: number, alt1: number) {
		this._lat0 = lat0;
		this._lon0 = lon0;
		this._alt0 = alt0;
		this._lat1 = lat1;
		this._lon1 = lon1;
		this._alt1 = alt1;
	}

	public get lat0(): number {
		return this._lat0;
	}
	public get lon0(): number {
		return this._lon0;
	}
	public get alt0(): number {
		return this._alt0;
	}
	public get lat1(): number {
		return this._lat1;
	}
	public get lon1(): number {
		return this._lon1;
	}
	public get alt1(): number {
		return this._alt1;
	}

	public equals(other: MapDemoValues): boolean {
		return (
			this._lat0 == other._lat0 &&
			this._lon0 == other._lon0 &&
			this._alt0 == other._alt0 &&
			this._lat1 == other._lat1 &&
			this._lon1 == other._lon1 &&
			this._alt1 == other._alt1
		);
	}
}

class MapDemoService {
	private _values: MapDemoValues = new MapDemoValues(0, 0, 0, 0, 0, 0);
	private _subscribers: Set<() => void> = new Set();

	private readonly layer: MapShapeSet;
	private readonly path: MapPolyLine;

	constructor() {
		// Add the data source to the Cesium map and store the returned cleanup function
		this.layer = new MapShapeSet();
		demoMap.addShapes(this.layer);
		this.path = new MapPolyLine({
			// 	positions: [
			// 		{ lat: this._values.lat0, lon: this._values.lon0, alt: this._values.alt0 },
			// 		{ lat: this._values.lat1, lon: this._values.lon1, alt: this._values.alt1 },
			// 	],
			width: 6,
			color: Color.RED,
		});
		this.layer.addShape(this.path);
		this.path.addHoverListener(
			event => {
				console.log("Hovering over demo line");
			},
			() => {
				console.log("Mouse left demo line");
			},
		);
		this.path.addMouseButtonListener((type, event) => {
			console.log("Mouse button on demo line: " + type);
		});

		lifeCycle.onInit(async () => {
			const data = await backend.get<MapDemoValues>("/mapDemo/values");
			this._values = new MapDemoValues(data.lat0, data.lon0, data.alt0, data.lat1, data.lon1, data.alt1);
			demoMap.goTo(
				(this._values.lat0 + this._values.lat1) / 2,
				(this._values.lon0 + this._values.lon1) / 2,
				(this._values.alt0 + this._values.alt1) / 2 + 50000,
			);
			this._valuesChanged();
		});
		lifeCycle.onHeartBeat(async () => {
			const data = await backend.get<MapDemoValues>("/mapDemo/values");
			const newValues = new MapDemoValues(data.lat0, data.lon0, data.alt0, data.lat1, data.lon1, data.alt1);
			if (!this._values.equals(newValues)) {
				this._values = newValues;
				this._valuesChanged();
			}
		});
	}

	public getValues(): MapDemoValues {
		return this._values;
	}

	public get visible(): boolean {
		return this.path.visible;
	}

	public set visible(visible: boolean) {
		this.path.visible = visible;
		demoMap.redraw();
		for (const callback of this._subscribers) {
			callback();
		}
	}

	public get width(): number {
		return this.path.width;
	}
	public set width(width: number) {
		this.path.width = width;
		for (const callback of this._subscribers) {
			callback();
		}
	}

	public get color(): Color {
		return this.path.color;
	}
	public set color(color: Color) {
		this.path.color = color;
		for (const callback of this._subscribers) {
			callback();
		}
	}

	public set lat0(value: number) {
		this._values = new MapDemoValues(
			value,
			this._values.lon0,
			this._values.alt0,
			this._values.lat1,
			this._values.lon1,
			this._values.alt1,
		);
		backend.put("/mapDemo/lat/0/" + value);
		this._valuesChanged();
	}
	public set lon0(value: number) {
		this._values = new MapDemoValues(
			this._values.lat0,
			value,
			this._values.alt0,
			this._values.lat1,
			this._values.lon1,
			this._values.alt1,
		);
		backend.put("/mapDemo/lon/0/" + value);
		this._valuesChanged();
	}
	public set alt0(value: number) {
		this._values = new MapDemoValues(
			this._values.lat0,
			this._values.lon0,
			value,
			this._values.lat1,
			this._values.lon1,
			this._values.alt1,
		);
		backend.put("/mapDemo/alt/0/" + value);
		this._valuesChanged();
	}
	public set lat1(value: number) {
		this._values = new MapDemoValues(
			this._values.lat0,
			this._values.lon0,
			this._values.alt0,
			value,
			this._values.lon1,
			this._values.alt1,
		);
		backend.put("/mapDemo/lat/1/" + value);
		this._valuesChanged();
	}
	public set lon1(value: number) {
		this._values = new MapDemoValues(
			this._values.lat0,
			this._values.lon0,
			this._values.alt0,
			this._values.lat1,
			value,
			this._values.alt1,
		);
		backend.put("/mapDemo/lon/1/" + value);
		this._valuesChanged();
	}
	public set alt1(value: number) {
		this._values = new MapDemoValues(
			this._values.lat0,
			this._values.lon0,
			this._values.alt0,
			this._values.lat1,
			this._values.lon1,
			value,
		);
		backend.put("/mapDemo/alt/1/" + value);
		this._valuesChanged();
	}

	public subscribe(callback: () => void): () => void {
		this._subscribers.add(callback);
		return () => this._subscribers.delete(callback);
	}

	private _valuesChanged() {
		for (const callback of this._subscribers) {
			callback();
		}

		console.log("Updating dynamic line positions");
		this.path.geoPositions = [
			{ lat: this._values.lat0, lon: this._values.lon0, alt: this._values.alt0 },
			{ lat: this._values.lat1, lon: this._values.lon1, alt: this._values.alt1 },
		];

		demoMap.redraw();
	}

	// Call this if the service lifecycle ever unmounts entirely
	public dispose(): void {
		this.layer.removeFromMap();
	}
}

export const mapDemoService = new MapDemoService();
