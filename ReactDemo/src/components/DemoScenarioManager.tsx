import {useState, useSyncExternalStore} from "react";
import {Box, Button, Tooltip, Drawer, List, ListItemButton, ListItemText, ListItemIcon, Collapse, Checkbox} from "@mui/material";
import DemoScenarioSelector from "./DemoScenarioSelector";
import * as Styles from "../Styles";
import {scenarioService, backend} from "../services/services";
import BuildIcon from '@mui/icons-material/Build';
import CloseIcon from '@mui/icons-material/Close';
import ExpandLessIcon from '@mui/icons-material/ExpandLess';
import ExpandMoreIcon from '@mui/icons-material/ExpandMore';
import PersonIcon from '@mui/icons-material/Person';
import DeleteIcon from '@mui/icons-material/Delete';

const scenariosSelected : Record<string, boolean> = {};
const usersExpanded: Record<string, boolean> = {};

const DemoScenarioManager = () => {
	const [scenarioManagerOpen, setScenarioManagerOpen] = useState(false);
	const [myScenariosOpen, setMyScenariosOpen] = useState(true);
	const myScenarios=useSyncExternalStore(
		scenarioService.onScenarioSetChange,
		()=>scenarioService.getScenariosOwnedBy(scenarioService.me)
	);
	const otherUsers=useSyncExternalStore(
		scenarioService.onScenarioSetChange,
		()=>scenarioService.scenarioOwners
	);
	const [selectedScenarios, setSelectedScenarios] = useState([]);
	
	const openScenarioManager=()=>setScenarioManagerOpen(true);
	const closeScenarioManager=()=>setScenarioManagerOpen(false);
	
	const setScenarioSelected=(scenarioId: string, selected: boolean)=>{
		if(selected){
			scenariosSelected[scenarioId]=true;
			setSelectedScenarios([...selectedScenarios, scenarioId]);
		} else {
			delete scenariosSelected[scenarioId];
			const newSelectedScenarios=[...selectedScenarios];
			const index=newSelectedScenarios.indexOf(scenarioId);
			if(index>=0)
				newSelectedScenarios.splice(index, 1);
			setSelectedScenarios(newSelectedScenarios);
		}
	}

	const [expandedUsers, setExpandedUsers] = useState([]);
	const isUserExpanded=(userId: string)=>{
		return true==usersExpanded[userId];
	};
	const setUserExpanded=(userId: string, expanded: boolean)=>{
		if(expanded){
			usersExpanded[userId]=true;
			setSelectedScenarios([...expandedUsers, userId]);
		} else {
			delete usersExpanded[userId];
			const newExpandedUsers=[...expandedUsers];
			const index=newExpandedUsers.indexOf(userId);
			if(index>=0)
				newExpandedUsers.splice(index, 1);
			setExpandedUsers(newExpandedUsers);
		}
	};
	
	const deleteSelectedScenarios=()=>{
		let request="/scenarios/delete";
		let first=true;
		for(const scenario of selectedScenarios){
			if(first)
				first=false;
			else
				request+=",";
			request+=scenario;
		}
		backend.get<void>(request);
	}

	return (
		<Box sx={Styles.horizontalLayout}>
			<DemoScenarioSelector />
			<Tooltip title="Manage Scenarios">
				<Button
					variant="contained"
					onClick={openScenarioManager}>
					<BuildIcon />
				</Button>
			</Tooltip>
			<Drawer
				open={scenarioManagerOpen}
				onClose={closeScenarioManager}>
				<Box sx={Styles.verticalLayout}>
					<Box sx={Styles.horizontalLayout}>
						Manage Scenarios
						<CloseIcon onClick={closeScenarioManager} />
					</Box>
					<List sx={{width: "100%", maxWidth: 400, bgcolor: "background.paper"}} component="nav">
						<ListItemButton onClick={e=>setMyScenariosOpen(!myScenariosOpen)}>
							<ListItemText>My Scenarios</ListItemText>
							{myScenariosOpen ? <ExpandLessIcon /> : <ExpandMoreIcon />}
						</ListItemButton>
						<Collapse in={myScenariosOpen} timeout="auto" unmountOnExit>
							<List component="div" disablePadding>
								{
									myScenarios.map(scenario=>(
										<ListItemButton
											key={scenario.id}
											sx={{pl:4}}>
											<Checkbox
												checked={scenario.canDelete && true==scenariosSelected[scenario.id]}
												onChange={e=>setScenarioSelected(scenario.id, e.target.checked)}
												disabled={!scenario.canDelete} />
											<ListItemText>{scenario.name}</ListItemText>
										</ListItemButton>
									))
								}
							</List>
						</Collapse>
						{
							otherUsers.map(user=>{
								let userDescrip=user.userName;
								if(user.fullName)
									userDescrip+=" ("+user.fullName+")";
								const userScenarios=scenarioService.getScenariosOwnedBy(user);
								<>
									<ListItemButton onClick={e=>setUserExpanded(user.id, !isUserExpanded(user.id))}>
										<ListItemIcon>
											<PersonIcon />
										</ListItemIcon>
										<ListItemText>userDescrip</ListItemText>
										{isUserExpanded(user.id) ? <ExpandLessIcon /> : <ExpandMoreIcon />}
									</ListItemButton>
									<Collapse in={isUserExpanded(user.id)} timeout="auto" unmountOnExit>
										<List component="div" disablePadding>
											{
												userScenarios.map(scenario=>{
													<ListItemButton
														key={scenario.id}
														sx={{pl:4}}>
														<Checkbox
															checked={scenario.canDelete && true==scenariosSelected[scenario.id]}
															onChange={e=>setScenarioSelected(scenario.id, e.target.checked)}
															disabled={!scenario.canDelete} />
														<ListItemText>{scenario.name}</ListItemText>
													</ListItemButton>
												})
											}
										</List>
									</Collapse>
								</>
							})
						}
					</List>
					<Button onClick={e=>deleteSelectedScenarios()}>
						<DeleteIcon />
					</Button>
				</Box>
			</Drawer>
		</Box>
	);
};

export default DemoScenarioManager;
