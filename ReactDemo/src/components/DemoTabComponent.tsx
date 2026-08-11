import React, {useEffect} from 'react';
import { DemoTab } from '../services/DemoTabService';
import { tabService } from '../services/services';

interface TabProps {
    id: string;
    priority: number;
    title: string;
    content: React.ComponentType;
}

const DemoTabComponent: React.FC<TabProps> = ({ id, priority, title, content }) => {
    useEffect(() => {
        // Instantiate and register only when component mounts safely
        const tab = new DemoTab(id, priority, title, content);
        tabService.addTab(tab);

        // Optional: return cleanup if tabService supports removal
        return () => tab.remove();
    }, [id, priority, title, content]); // Re-register if props change

    const tab = new DemoTab(id, priority, title, content);
    tabService.addTab(tab);
    return null; // This component does not render anything, it just registers the tab with the service
}

export default DemoTabComponent;
