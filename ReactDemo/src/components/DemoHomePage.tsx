import { useState, useRef } from "react";
import { Box } from '@mui/material';
import DemoMenuBar from "./DemoMenuBar";
import DemoMap from "./DemoMap";
import DemoBottomBar from "./DemoBottomBar"
import DemoTabs from "./DemoTabs";
import DemoTabComponent from "./DemoTabComponent";
import MapDemo from "../tabs/MapDemo.tsx";

const DemoHomePage = () => {
	// Draggable Split Panel State
	const [leftWidth, setLeftWidth] = useState(50);
	const isDragging = useRef(false);

	// Dragging logic for the divider
	const startResize = (e: React.MouseEvent) => {
		isDragging.current = true;
		document.addEventListener('mousemove', resize);
		document.addEventListener('mouseup', stopResize);
	};

	const resize = (e: MouseEvent) => {
		if (!isDragging.current) return;
		const container = document.getElementById('split-container');
		if (!container) return;

		const containerRect = container.getBoundingClientRect();
		const newLeftWidth = ((e.clientX - containerRect.left) / containerRect.width) * 100;

		if (newLeftWidth > 10 && newLeftWidth < 90) {
			setLeftWidth(newLeftWidth);
		}
	};

	const stopResize = () => {
		isDragging.current = false;
		document.removeEventListener('mousemove', resize);
		document.removeEventListener('mouseup', stopResize);
	};

	const rootStyle = { display: 'flex', flexDirection: 'column', height: '100vh', overflow: 'hidden' };
	const mainSplitStyle = {
		flexGrow: 1,
		display: 'flex',
		flexDirection: 'row',
		overflow: 'hidden',
		position: 'relative'
	};
	const splitLeftStyle = { width: `${leftWidth}%`, overflow: 'auto', bgcolor: '#f5f5f5' };
	const splitterStyle = {
		width: '8px',
		backgroundColor: '#e0e0e0',
		cursor: 'col-resize',
		transition: 'background-color 0.2s',
		'&:hover': { backgroundColor: '#1976d2' },
		zIndex: 10
	};
	const splitRightStyle = { width: `${100 - leftWidth}%`, overflow: 'auto', bgcolor: '#fafafa' };

	return (
		<Box sx={rootStyle}>

			<DemoMenuBar />

			{/* 2. Main Split Panel Container */}
			<Box
				id="split-container"
				sx={mainSplitStyle}
			>
				<Box sx={splitLeftStyle}>
					<DemoMap />
				</Box>

				<Box
					onMouseDown={startResize}
					sx={splitterStyle}
				/>

				<Box sx={splitRightStyle}>
					<DemoTabs>
						<DemoTabComponent id="demo0" priority={0} title="Map Demo" content={MapDemo} />
					</DemoTabs>
				</Box>
			</Box>

			<DemoBottomBar />
		</Box>
	);
};

export default DemoHomePage;
