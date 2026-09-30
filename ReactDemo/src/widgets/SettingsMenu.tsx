import { IconButton, Popover } from "@mui/material";
import React, { useState } from "react";
import SettingsIcon from "@mui/icons-material/Settings";

const SettingsMenu: React.FC<{ content: React.ReactNode }> = ({ content }) => {
	const [settingsAnchor, setSettingsAnchor] = useState(null);
	// const settingsOpen = Boolean(settingsAnchor);
	const openSettings = (e: React.MouseEvent<HTMLButtonElement>) => setSettingsAnchor(e.currentTarget);
	const closeSettings = () => setSettingsAnchor(null);

	return (
		<>
			<IconButton onClick={openSettings}>
				<SettingsIcon />
			</IconButton>
			<Popover
				open={Boolean(settingsAnchor)}
				anchorEl={settingsAnchor}
				onClose={closeSettings}
				anchorOrigin={{
					vertical: "top",
					horizontal: "right",
				}}>
				{content}
			</Popover>
		</>
	);
};

export default SettingsMenu;
