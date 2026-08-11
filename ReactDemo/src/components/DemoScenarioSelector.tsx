import {useState, useSyncExternalStore} from "react";
import {Select, MenuItem} from "@mui/material";

import {User} from "../values/User";
import {Scenario} from "../values/Scenario";
import {backend, scenarioService} from "../services/services.ts";

const DemoScenarioSelector = () => {
	const scenarioIds=useSyncExternalStore(
		scenarioService.onScenarioSetChange,
		()=>scenarioService.scenarioIds
	);
	const selectedScenarioId=useSyncExternalStore(
		scenarioService.onSelectedScenarioChange,
		()=>scenarioService.selectedScenario?.id ?? null
	);
	
	return (
		<Select
			value={selectedScenarioId ?? ""}
			onChange={e=>scenarioService.selectedScenario=e.target.value as string}
			size="small"
		>
			{
				scenarioIds.map(scenarioId => {
					const scenario: Scenario =scenarioService.getScenario(scenarioId);
					if(!scenario)
						return null;
					return (
						<MenuItem key={scenarioId} value={scenarioId}>
							{scenarioService.describeScenario(scenario)}
						</MenuItem>
					);
				})
			}
		</Select>
	);
};

export default DemoScenarioSelector;
