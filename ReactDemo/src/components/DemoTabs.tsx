import { useSyncExternalStore } from "react";
import { tabService } from "../services/services";
import { Box, Tabs, Tab } from "@mui/material";

interface TabsProps {
	children: React.ReactNode;
}

const DemoTabs: React.FC<TabsProps> = ({ children }) => {
	// We don't do anything with the tabs--they register themselves.

	const tabs = useSyncExternalStore(
		sub => tabService.subscribeToTabSetChanges(sub),
		() => tabService.visibleTabs,
	);
	const selectedTab = useSyncExternalStore(
		sub => tabService.subscribeToSelectedTabChanges(__ => sub()),
		() => tabService.selectedTab,
	);

	const ActiveView = selectedTab?.content;

	const handleTabChange = (event: React.SyntheticEvent, newValue: string) => {
		const tab = tabService.getTabById(newValue);
		if (tab) {
			tab.setSelected();
		}
	};

	return (
		<Box sx={{ width: "100%", height: "100%" }}>
			<Tabs
				value={selectedTab?.id ?? false}
				onChange={handleTabChange}
				variant="scrollable"
				scrollButtons="auto">
				{tabs.map(tab => (
					<Tab
						key={tab.id}
						label={tab.title}
						value={tab.id}
						style={{ fontWeight: tab.selected ? "bold" : "normal" }}
					/>
				))}
			</Tabs>
			{/* Only the selected tab's DOM structure is installed here */}
			<Box sx={{ p: 2 }}>{ActiveView && <ActiveView />}</Box>
			{/*
			Force React to read and mount the tabs.
        	Because DemoTabComponent returns null, this introduces 0 DOM overhead.
		 	*/}
			{children}{" "}
		</Box>
	);
};

export default DemoTabs;
