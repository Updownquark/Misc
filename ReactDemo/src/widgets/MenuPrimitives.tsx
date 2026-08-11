/* Because I'm writing a desktop-style app using a mobile-oriented framework (Material), I have to make some of my own custom components
 * that extends base-UI components so they look and work right.
 */

import { Menu as BaseMenu } from "@base-ui/react/menu";
import { Menubar as BaseMenubar } from "@base-ui/react/menubar";
// 1. Pull in the Material UI Theme Hook tool
import { useTheme } from "@mui/material/styles";

export const Menubar = {
	Root: (props: any) => {
		const theme = useTheme(); // Access your app's live theme tokens
		return (
			<BaseMenubar
				style={{
					display: 'flex',
					backgroundColor: theme.palette.background.default,
					padding: '8px',
					borderBottom: `1px solid ${theme.palette.divider}`,
					gap: '8px'
				}}
				{...props}
			/>
		);
	},
};

export const Menu = {
	Root: BaseMenu.Root,
	Portal: BaseMenu.Portal,

	Positioner: (props: any) => (
		<BaseMenu.Positioner style={{ zIndex: 9999 }} {...props} />
		/*<BaseMenu.Positioner
			// Use a functional assignment to merge your properties with Base UI's layout parameters
			style={(state: any) => ({
				zIndex: 9999, // Keep your top layer stack priority safety boundary
				...state.style, // CRITICAL: This injects the automatic trigger tracking math back into the DOM!
			})}
			{...props}
		/>*/
	),

	Popup: (props: any) => {
		const theme = useTheme();
		return (
			<BaseMenu.Popup
				style={{
					minWidth: '180px',
					// Pull surface colors and elevations natively from Material UI
					backgroundColor: theme.palette.background.paper,
					color: theme.palette.text.primary,
					border: `1px solid ${theme.palette.divider}`,
					borderRadius: `${theme.shape.borderRadius}px`,
					padding: '4px',
					// Grabs the standard popup dropdown shadow from Material Design
					boxShadow: theme.shadows[4],
					outline: 'none',
				}}
				{...props}
			/>
		);
	},

	Item: (props: any) => {
		const theme = useTheme();
		return (
			<BaseMenu.Item
				style={(state: any) => ({
					padding: '6px 12px',
					fontSize: theme.typography.body2.fontSize,
					fontFamily: theme.typography.fontFamily,
					color: theme.palette.text.secondary,
					backgroundColor: state.highlighted
						? theme.palette.action.hover
						: 'transparent',
					borderRadius: `${theme.shape.borderRadius}px`,
					cursor: 'pointer',
					outline: 'none',
					display: 'flex',
					alignItems: 'center',
					gap: '8px',
				})}
				{...props}
			/>
		);
	},

	Trigger: (props: any) => {
		const theme = useTheme();
		return (
			<BaseMenu.Trigger
			className="app-menu-trigger"
				style={(state: any) => ({
					border: 'none',
					padding: '6px 12px',
					fontSize: theme.typography.body2.fontSize,
					fontFamily: theme.typography.fontFamily,
					fontWeight: theme.typography.fontWeightMedium,
					borderRadius: `${theme.shape.borderRadius}px`,
					cursor: 'pointer',
					outline: 'none',
					color: theme.palette.text.primary,
					background: state.open
						? theme.palette.action.selected
						: 'transparent',
				})}
				{...props}
			/>
		);
	},

	Group: BaseMenu.Group,

	GroupLabel: (props: any) => {
		const theme = useTheme();
		return (
			<div
				style={{
					padding: '6px 12px 2px 12px',
					fontSize: '11px',
					fontFamily: theme.typography.fontFamily,
					fontWeight: theme.typography.fontWeightBold,
					textTransform: 'uppercase',
					color: theme.palette.text.disabled,
					letterSpacing: '0.05em',
				}}
				{...props}
			/>
		);
	},

	Separator: (props: any) => {
		const theme = useTheme();
		return (
			<BaseMenu.Separator
				style={{
					height: '1px',
					backgroundColor: theme.palette.divider,
					margin: '4px 0',
				}}
				{...props}
			/>
		);
	},

	SubmenuRoot: BaseMenu.SubmenuRoot,
	SubmenuTrigger: (props: any) => {
		const theme = useTheme();
		return (
			<BaseMenu.SubmenuTrigger
				style={(state: any) => ({
					padding: '6px 12px',
					fontSize: theme.typography.body2.fontSize,
					fontFamily: theme.typography.fontFamily,
					color: theme.palette.text.secondary,
					backgroundColor: state.highlighted
						? theme.palette.action.hover
						: 'transparent',
					borderRadius: `${theme.shape.borderRadius}px`,
					cursor: 'pointer',
					outline: 'none',
					display: 'flex',
					alignItems: 'center',
					gap: '8px',
					width: '100%',
					boxSizing: 'border-box',
					border: 'none',
				})}
				{...props}
			/>
		);
	},
};
