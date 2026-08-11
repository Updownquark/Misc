import {useState} from "react";
import { Paper, Box } from "@mui/material";
import DemoScenarioManager from "./DemoScenarioManager";
import * as Styles from "../Styles";

const bottomBarStyle = {
	p: 1.5,
	bgcolor: '#eeeeee',
	display: 'flex',
	flexDirection: 'row',
	gap: 2,
	alignItems: 'center',
	borderTop: '1px solid #ccc'
};
const placeholderBoxStyle = { p: 1, bgcolor: 'white', borderRadius: 1, boxShadow: 1 };

const DemoBottomBar = () => {
	return (
		<Paper
			elevation={3}
			square
			sx={bottomBarStyle}
		>
			<Box sx={Styles.verticalLayout}>
				{/* Maybe the play controls will go here */}
				<DemoScenarioManager />
			</Box>
			<Box sx={placeholderBoxStyle}>Widget 1</Box>
			<Box sx={placeholderBoxStyle}>Widget 2</Box>
			<Box sx={placeholderBoxStyle}>Widget 3</Box>
		</Paper>
	);
};

export default DemoBottomBar;
