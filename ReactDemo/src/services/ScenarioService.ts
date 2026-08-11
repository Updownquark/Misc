import DemoBackend from "./DemoBackend";
import { Scenario, scenariosEqual } from "../values/Scenario";
import { User } from "../values/User";
import { binarySearch, compareNumberTolerant } from "../util/Utils";
import { LifeCycleService } from "./LifeCycleService";
import { BACKEND_API_URL } from "../config/backend";

type Listener = () => void;

class ScenarioService {
	private readonly EMPTY_SCENARIOS : readonly Scenario [] = [];
	private readonly _backend: DemoBackend;
	private _me: User | null = null;

	private _scenarios: Scenario[] = [];
	private readonly _scenariosById: Record<string, Scenario> = {};
	private _scenarioIds: string[] = [];
	private _selectedScenario: Scenario | null = null;
	private readonly _scenariosByOwner: Record<string, Scenario[]> = {};
	private _scenarioOwners: User[] = [];

	private readonly _selectedScenarioSubscribers = new Set<Listener>();
	private readonly _scenarioSetSubscribers = new Set<Listener>();

	constructor(lifeCycle: LifeCycleService, backend: DemoBackend) {
		this._backend = backend;

		lifeCycle.onInit(() => this.__init());

		lifeCycle.onHeartBeat(() => this.syncScenarios());
	}

	private async __init(): Promise<void> {
		this._me = await this._backend.me;
		await this.__getSelectedScenario();
	}

	private __getSelectedScenario = async (): Promise<Scenario> => {
		const scenario = await this._backend.get<Scenario>("/my-data/selected-scenario");
		document.title = "Spring+React Demo: " + (scenario == null ? "No Scenario Selected" : this.describeScenario(scenario));
		this._selectedScenario = scenario;
		return scenario;
	}

	public get me(): User | null {
		return this._me;
	}

	public get selectedScenario(): Scenario | null {
		return this._selectedScenario;
	}

	public set selectedScenario(scenarioId: string) {
		if (this._selectedScenario != null && this._selectedScenario.id == scenario)
			return;
		const scenario = this._scenariosById[scenarioId];
		if (scenario == null)
			throw "No such scenario with ID " + scenarioId;

		this._selectedScenario = scenario;
		document.title = "Spring+React Demo: " + (scenario == null ? "No Scenario Selected" : scenario.name);
		this._selectedScenarioSubscribers.forEach(sub => {
			try {
				sub.run();
			} catch (error) {
				console.error("Selected scenario subscriber failed: ", sub, error);
			}
		});
	}

	public get scenarios(): readonly Scenario[] {
		return this._scenarios;
	}

	public get scenarioIds(): readonly string[] {
		return this._scenarioIds;
	}

	public getScenario(scenarioId: string): Scenario | null {
		return this._scenariosById[scenarioId];
	}

	public get scenarioOwners(): readonly User[] {
		return this._scenarioOwners;
	}

	public getScenariosOwnedBy(owner: User): readonly Scenario[] {
		if(!owner)
			return this.EMPTY_SCENARIOS;
		const ownedScenarios = this._scenariosByOwner[owner.id];
		return ownedScenarios ?? this.EMPTY_SCENARIOS;
	}

	public compareScenarios(scenario1: Scenario, scenario2: Scenario): number {
		let comp = compareNumberTolerant(scenario1.name, scenario2.name);
		if (comp == 0 && scenario1.owner.id != scenario2.owner.id) {
			if (this.scenario1.owner.id == this._me.id)
				comp = -1;
			else if (scenario2.owner.id == this.me.id)
				comp = 1;
			else {
				comp = compareNumberTolerant(scenario1.owner.userName, scenario2.owner.userName);
				if (comp == 0 && scenario1.owner.fullName != null && scenario2.owner.fullName != null)
					comp = compareNumberTolerant(scenario1.owner.fullName, scenario2.owner.fullName);
			}
		}
		return comp;
	}

	public describeScenario(scenario: Scenario): string {
		let descrip = scenario.name;
		if (this._me != null && scenario.owner.id != this._me.id)
			descrip += " (" + scenario.owner.userName + ")";
		return descrip;
	}

	public syncScenarios = async (): Promise<void> => {
		const newScenarios = await this._backend.get<Scenario[]>("/scenarios");
		const removedScenarios = {}; //So we can detect scenarios that have been removed later
		this._scenarioIds.forEach(scenarioId => removedScenarios[scenarioId] = true); //Value doesn't matter
		let scenariosChanged = false;
		let ownersChanged=false;
		let selectedScenarioChanged = false;
		newScenarios.forEach(scenario => {
			const oldScenario: Scenario | null = this._scenariosById[scenario.id];
			if (oldScenario != null) { //We already know of this scenario
				delete removedScenarios[scenario.id]; //The scenario hasn't been removed 
				if (!scenariosEqual(oldScenario, scenario)) { //The scenario has changed
					scenariosChanged = true;
					this._reorderScenario(oldScenario, scenario)
					if (this._selectedScenario != null && this._selectedScenario.id == scenario.id) {
						selectedScenarioChanged = true;
						this._selectedScenario = scenario;
					}
				}
			} else { //The scenario is new to us
				scenariosChanged = true;
				let index = binarySearch(this._scenarios, s => this.compareScenarios(scenario, s));
				if (index < 0)
					index = -index - 1; //Transform to the index the scenario should be inserted at
				this._scenarios.splice(index, 0, scenario);
				this._scenarioIds.splice(index, 0, scenario.id);
				this._scenariosById[scenario.id] = scenario;
				// We're assuming here and elsewhere that the user's user name doesn't ever change while we're live
				let ownerScenarios = this._scenariosByOwner[scenario.owner.id];
				if (!ownerScenarios) {
					ownersChanged=true;
					ownerScenarios = [scenario];
					this._scenariosByOwner[scenario.owner.id] = ownerScenarios;
					if(scenario.owner.id!=this._me.id){
						let ownerIndex = binarySearch(this._scenarioOwners, o => compareNumberTolerant(scenario.owner.userName, o.userName));
						if (ownerIndex < 0)
							ownerIndex = -ownerIndex - 1;
						this._scenarioOwners.splice(ownerIndex, 0, scenario.owner);
					}
				} else {
					if(scenario.owner.id!=this._me.id){
						let ownerIndex = binarySearch(this._scenarioOwners, o => compareNumberTolerant(scenario.owner.userName, o.userName));
						if(ownerIndex<0)
							ownerIndex=-ownerIndex-1;
						this._scenarioOwners[ownerIndex] = scenario.owner; //Update
					}
					index = binarySearch(ownerScenarios, s => compareNumberTolerant(scenario.name, s.name));
					if (index < 0)
						index = -index - 1;
					ownerScenarios.splice(index, 0, scenario);
				}
			}
		});

		// Now deal with scenarios that have been removed or have become inaccessible
		for (const scenarioId in removedScenarios) {
			scenariosChanged = true;
			const scenario = this._scenariosById[scenarioId];
			delete this._scenariosById[scenarioId];
			const index = binarySearch(this._scenarios, _scenario => compareNumberTolerant(scenario.name, _scenario.name));
			if (index >= 0) {
				this._scenarios.splice(index, 1);
				this._scenarioIds.splice(index, 1);
			} else
				console.error("Deleted scenario not found by name: " + scenario.name);

			if (!selectedScenarioChanged && this._selectedScenario != null && this._selectedScenario.id == scenarioId) {
				//The selected scenario is gone.  The logic required to determine the new one only exists in the service.
				const newSelectedScenario = await this.__getSelectedScenario();
				selectedScenarioChanged = true;
			}

			const ownerScenarios = this._scenariosByOwner[scenario.owner.id];
			if (ownerScenarios.length == 1) {
				ownersChanged=true;
				delete this._scenariosByOwner[scenario.owner.id];
				if(scenario.owner.id!=this._me.id){
					const ownerIdx = binarySearch(this._scenarioOwners, owner => compareNumberTolerant(scenario.owner.userName, owner.userName));
					this._scenarioOwners.splice(ownerIdx, 1);
				}
			} else {
				const scenarioIdx = binarySearch(ownerScenarios, s => compareNumberTolerant(scenario.name, s.name));
				ownerScenarios.splice(scenarioIdx, 1);
			}
		}

		//Now fire any necessary events
		if (scenariosChanged) {
			// Re-assign the public references so React updates the UI
			this._scenarios= [...this._scenarios];
			this._scenarioIds= [...this._scenarioIds];
			if(ownersChanged)
				this._scenarioOwners=[...this._scenarioOwners];
			this._scenarioSetSubscribers.forEach(sub => {
				try {
					sub();
				} catch (error) {
					console.error("Scenario set subscriber failed: ", sub, error);
				}
			});
		}
		if (selectedScenarioChanged) {
			this._selectedScenarioSubscribers.forEach(sub => {
				try {
					sub();
				} catch (error) {
					console.error("Selected scenario subscriber failed: ", sub, error);
				}
			});
		}
	}

	private _reorderScenario(oldScenario: Scenario, scenario: Scenario): void {
		//Re-order in the all scenarios list
		const oldIndex = binarySearch(this._scenarios, s => this.compareScenarios(oldScenario, s));
		if (oldScenario.name != scenario.name) { //Scenario has been renamed, so we may need to move it in the sorted arrays
			let newIndex = binarySearch(this._scenarios, s => this.compareScenarios(scenario, s));
			if (newIndex < 0)
				newIndex = -newIndex - 1;
			//Gotta be careful here, because removing the old scenario could affect the new index
			if (oldIndex == newIndex) { //Name changed, but the ordering didn't
				this._scenarios[newIndex] = scenario;
			} else if (oldIndex < newIndex) { // The scenario now appears later in the array
				// Add the new item first, then remove the old item
				this._scenarios.splice(newIndex, 0, scenario);
				this._scenarioIds.splice(newIndex, 0, scenario.id)
				this._scenarios.splice(oldIndex, 1);
				this._scenarioIds.splice(oldIndex, 1);
			} else { //Remove the old item first, then add the new item
				this._scenarios.splice(oldIndex, 1);
				this._scenarioIds.splice(oldIndex, 1);
				this._scenarios.splice(newIndex, 0, scenario);
				this._scenarioIds.splice(newIndex, 0, scenario.id);
			}
		} else {
			this._scenarios[oldIndex] = scenario;
		}

		//Update the by-owner list(s)
		if (scenario.owner.id != oldScenario.owner.id) {
			const oldUserScenarios = this._scenariosByOwner[oldScenario.owner.id];
			if (oldUserScenarios.length == 1) {
				delete this._scenariosByOwner[oldScenario.owner.id];
				if(oldScenario.owner.id!=this._me.id){
					const ownerIdx = binarySearch(this._scenarioOwners, owner => compareNumberTolerant(oldScenario.owner.userName, owner.userName));
					this._scenarioOwners.splice(ownerIdx, 1);
				}
			} else {
				const scenarioIdx = binarySearch(oldUserScenarios, s => compareNumberTolerant(oldScenario.name, s.name));
				oldUserScenarios.splice(scenarioIdx, 1);
			}
			let newUserScenarios = this._scenariosByOwner[scenario.owner.id];
			if (!newUserScenarios) {
				newUserScenarios = [scenario];
				this._scenariosByOwner[scenario.owner.id] = newUserScenarios;
				if(scenario.owner.id!=this._me.id){
					let ownerIdx = binarySearch(this._scenarioOwners, owner => compareNumberTolerant(scenario.owner.userName, owner.userName));
					if (ownerIdx < 0)
						ownerIdx = -ownerIdx - 1;
					this._scenarioOwners.splice(ownerIdx, 0, scenario.owner);
				}
			} else {
				let scenarioIdx = binarySearch(newUserScenarios, s => compareNumberTolerant(scenario.name, s.name));
				if (scenarioIdx < 0)
					scenarioIdx = -scenarioIdx - 1;
				newUserScenarios.splice(scenarioIdx, 0, scenario);
			}
		} else if (scenario.owner.id!=this._me.id && oldScenario.name != scenario.name) { //Move the scenario in the user's grouped scenarios
			const ownerScenarios = this._scenariosByOwner[oldScenario.owner.id];
			const oldScenarioIndex = binarySearch(ownerScenarios, s => compareNumberTolerant(oldScenario.name, s.name));
			let newScenarioIndex = binarySearch(ownerScenarios, s => compareNumberTolerant(scenario.name, s.name));
			if(newScenarioIndex<0)
				newScenarioIndex=-newScenarioIndex-1;
			if (oldScenarioIndex == newScenarioIndex) { //Name changed, but the ordering didn't
				ownerScenarios[newScenarioIndex] = scenario;
			} else if (oldScenarioIndex < newScenarioIndex) { // The scenario now appears later in the array
				// Add the new item first, then remove the old item
				ownerScenarios.splice(newScenarioIndex, 0, scenario);
				ownerScenarios.splice(oldScenarioIndex, 1);
			} else { //Remove the old item first, then add the new item
				ownerScenarios.splice(oldScenarioIndex, 1);
				ownerScenarios.splice(newScenarioIndex, 0, scenario);
			}
		}
	}

	public onScenarioSetChange = (callback: Listener): () => void => {
		this._scenarioSetSubscribers.add(callback);
		return () => this._scenarioSetSubscribers.delete(callback);
	}

	public onSelectedScenarioChange = (callback: Listener): () => void => {
		this._selectedScenarioSubscribers.add(callback);
		return () => this._selectedScenarioSubscribers.delete(callback);
	}
}

export default ScenarioService;
