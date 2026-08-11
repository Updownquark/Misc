// src/components/WorldWindGlobe.tsx
import React, { useEffect, useRef } from "react";
import { demoMap } from "../services/services";

export const MapWidget: React.FC = () => {
	// mutable WorldWindow reference instance
	const cesiumContainerRef = useRef<HTMLDivElement | null>(null);

	useEffect(() => {
		if(cesiumContainerRef.current)
			demoMap.init(cesiumContainerRef.current);

		// Clean up the viewer instance when the component unmounts
		return () => demoMap.destroy();
	}, []);

	const containerStyle: React.CSSProperties = {
		width: "100%",
		height: "100%",
		margin: 0,
		padding: 0,
		overflow: "hidden",
	};

	return (
		<div
			ref={cesiumContainerRef}
			style={containerStyle}
		/>
	);
};

export default MapWidget;
