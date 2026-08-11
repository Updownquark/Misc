import { mapDemoService } from "../services/MapDemoService";
import { useSyncExternalStore } from "react";
import { Grid } from "@mui/material";
import DemoTextField from "../widgets/DemoTextField";

function validateLat(value: number): string | null {
	if (value < -90 || value > 90) {
		return "Latitude must be between -90 and 90";
	}
}

function validateLon(value: number): string | null {
	if (value < -180 || value > 180) {
		return "Longitude must be between -180 and 180";
	}
}

const MapDemo = () => {
	const values = useSyncExternalStore(
		sub => mapDemoService.subscribe(sub),
		() => mapDemoService.getValues(),
	);

	return (
		<Grid
			container
			spacing={2}>
			<Grid size={2}>
				<b>Coords</b>
			</Grid>
			<Grid size={4}>
				<b>Latitude</b>
			</Grid>
			<Grid size={4}>
				<b>Longitude</b>
			</Grid>
			<Grid size={2}>
				<b>Altitude</b>
			</Grid>

			<Grid size={2}>
				<b>Point 1</b>
			</Grid>
			<Grid size={4}>
				<DemoTextField
					value={values.lat0}
					onChange={v => (mapDemoService.lat0 = v)}
					parser={parseFloat}
					validator={validateLat}
				/>
			</Grid>
			<Grid size={4}>
				<DemoTextField
					value={values.lon0}
					onChange={v => (mapDemoService.lon0 = v)}
					parser={parseFloat}
					validator={validateLon}
				/>
			</Grid>
			<Grid size={2}>
				<DemoTextField
					value={values.alt0}
					onChange={v => (mapDemoService.alt0 = v)}
					parser={parseFloat}
				/>
			</Grid>

			<Grid size={2}>
				<b>Point 2</b>
			</Grid>
			<Grid size={4}>
				<DemoTextField
					value={values.lat1}
					onChange={v => (mapDemoService.lat1 = v)}
					parser={parseFloat}
					validator={validateLat}
				/>
			</Grid>
			<Grid size={4}>
				<DemoTextField
					value={values.lon1}
					onChange={v => (mapDemoService.lon1 = v)}
					parser={parseFloat}
					validator={validateLon}
				/>
			</Grid>
			<Grid size={2}>
				<DemoTextField
					value={values.alt1}
					onChange={v => (mapDemoService.alt1 = v)}
					parser={parseFloat}
				/>
			</Grid>
		</Grid>
	);
};

export default MapDemo;
