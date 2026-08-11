import { Menubar, Menu } from "../widgets/MenuPrimitives"; 
import InfoIcon from '@mui/icons-material/Info';
import SettingsIcon from '@mui/icons-material/Settings';
import BuildIcon from '@mui/icons-material/Build';
import MonitorHeartIcon from '@mui/icons-material/MonitorHeart';
import TimelineIcon from '@mui/icons-material/Timeline';
import InsertDriveFileIcon from '@mui/icons-material/InsertDriveFile';
import ToggleOnIcon from '@mui/icons-material/ToggleOn';
import TuneIcon from '@mui/icons-material/Tune';
import VisibilityOffIcon from '@mui/icons-material/VisibilityOff';
import VisibilityIcon from '@mui/icons-material/Visibility';
import UndoIcon from '@mui/icons-material/Undo';
import RedoIcon from '@mui/icons-material/Redo';
import ContentCutIcon from '@mui/icons-material/ContentCut';
import ContentCopyIcon from '@mui/icons-material/ContentCopy';
import ContentPasteIcon from '@mui/icons-material/ContentPaste';

const DemoMenuBar = () => {
  return (
    <Menubar.Root>
      <Menu.Root>
        <Menu.Trigger>App</Menu.Trigger>
        <Menu.Portal>
          <Menu.Positioner sideOffset={4} alignOffset={-2}>
            <Menu.Popup>
              
              <Menu.Group>
                <Menu.GroupLabel>General</Menu.GroupLabel>
                <Menu.Item><InfoIcon fontSize="small" /> About</Menu.Item>
                <Menu.Item><SettingsIcon fontSize="small" /> Settings...</Menu.Item>
              </Menu.Group>
              
              <Menu.Separator />
              
              {/* Nested Sub-Menu Section */}
              <Menu.SubmenuRoot>
                <Menu.SubmenuTrigger>
                  <BuildIcon fontSize="small" /> Services
                </Menu.SubmenuTrigger>
                <Menu.Portal>
                  <Menu.Positioner alignOffset={-4}>
                    <Menu.Popup>
                      <Menu.Group>
                        <Menu.GroupLabel>Development</Menu.GroupLabel>
                        <Menu.Item><MonitorHeartIcon fontSize="small" /> Activity Monitor</Menu.Item>
                        <Menu.Item><TimelineIcon fontSize="small" /> System Trace</Menu.Item>
                        <Menu.Item><InsertDriveFileIcon fontSize="small" /> File Activity</Menu.Item>
                      </Menu.Group>
                      <Menu.Separator />
                      <Menu.Group>
                        <Menu.GroupLabel>Shortcuts</Menu.GroupLabel>
                        <Menu.Item><ToggleOnIcon fontSize="small" /> Toggle Gate</Menu.Item>
                        <Menu.Item><TuneIcon fontSize="small" /> Services Settings...</Menu.Item>
                      </Menu.Group>
                    </Menu.Popup>
                  </Menu.Positioner>
                </Menu.Portal>
              </Menu.SubmenuRoot>
              
              <Menu.Separator />
              
              <Menu.Group>
                <Menu.GroupLabel>Window</Menu.GroupLabel>
                <Menu.Item><VisibilityOffIcon fontSize="small" /> Hide App</Menu.Item>
                <Menu.Item><VisibilityOffIcon fontSize="small" /> Hide Others</Menu.Item>
                <Menu.Item><VisibilityIcon fontSize="small" /> Show All</Menu.Item>
              </Menu.Group>
              
            </Menu.Popup>
          </Menu.Positioner>
        </Menu.Portal>
      </Menu.Root>

      <Menu.Root>
        <Menu.Trigger>Edit</Menu.Trigger>
        <Menu.Portal>
          <Menu.Positioner sideOffset={4}>
            <Menu.Popup>
              <Menu.Group>
                <Menu.GroupLabel>History</Menu.GroupLabel>
                <Menu.Item><UndoIcon fontSize="small" /> Undo</Menu.Item>
                <Menu.Item><RedoIcon fontSize="small" /> Redo</Menu.Item>
              </Menu.Group>
              <Menu.Separator />
              <Menu.Group>
                <Menu.GroupLabel>Clipboard</Menu.GroupLabel>
                <Menu.Item><ContentCutIcon fontSize="small" /> Cut</Menu.Item>
                <Menu.Item><ContentCopyIcon fontSize="small" /> Copy</Menu.Item>
                <Menu.Item><ContentPasteIcon fontSize="small" /> Paste</Menu.Item>
              </Menu.Group>
            </Menu.Popup>
          </Menu.Positioner>
        </Menu.Portal>
      </Menu.Root>
    </Menubar.Root>
  );
};

export default DemoMenuBar;
