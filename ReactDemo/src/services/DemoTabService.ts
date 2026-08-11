import React from "react";
import { binarySearch, compareNumberTolerant } from "../util/Utils";

export class DemoTab {
	public readonly id: string;
	private readonly _priority: number;
	private _title: string;
	private readonly _content: React.ComponentType;
	private _visible: boolean = true;
	private _selected: boolean = false;
	private _service: DemoTabService | null = null;

	constructor(id: string, priority: number, title: string, content: React.ComponentType) {
		this.id = id;
		this._priority = priority;
		this._title = title;
		this._content = content;
	}

	__setService(service: DemoTabService): void {
		this._service = service;
	}

	public get priority(): number {
		return this._priority;
	}

	public get title(): string {
		return this._title;
	}

	public set title(newTitle: string) {
		if (this._service) {
			this._service.__setTabTitle(this, newTitle);
		}
	}

	__setTabTitle(newTitle: string): void {
		this._title = newTitle;
	}

	public get content(): React.ComponentType {
		return this._content;
	}

	public get visible(): boolean {
		return this._visible;
	}

	public set visible(newVisible: boolean) {
		if (this._visible === newVisible) return;
		this._visible = newVisible;
		if (this._service) {
			this._service.__setTabVisibility(this, newVisible);
		}
	}

	public get selected(): boolean {
		return this._selected;
	}

	public setSelected(): void {
		this._selected = true;
		if (this._service) this._service.__setSelectedTab(this);
	}

	__setSelected(selected: boolean): void {
		this._selected = selected;
	}

	public remove(): void {
		if (this._service) {
			this._service.__removeTab(this);
		}
	}

	public compareTo(other: DemoTab): number {
		let comp = other._priority - this._priority;
		if (comp == 0) comp = compareNumberTolerant(this._title, other._title);
		return comp;
	}
}

type TabSetListener = () => void;
type SelectedTabListener = (selectedTab: DemoTab | null) => void;

class DemoTabService {
	private readonly _tabsById: Map<string, DemoTab> = new Map();
	private _allTabs: DemoTab[] = [];
	private _visibleTabs: DemoTab[] = [];
	private _selectedTab: v | null = null;
	private readonly _tabSetSubscribers: TabSetListener[] = [];
	private readonly _selectedTabSubscribers: SelectedTabListener[] = [];

	public addTab(tab: DemoTab): void {
		if (this._tabsById.has(tab.id)) {
			//Tab has already been added
			return;
		}
		this._tabsById.set(tab.id, tab);
		let index = binarySearch(this._allTabs, other => tab.compareTo(other));
		if (index < 0) index = -index - 1;
		this._allTabs.splice(index, 0, tab);
		// Re-assign the reference so React updates the UI
		this._allTabs = [...this._allTabs];
		if (tab.visible) {
			let index = binarySearch(this._visibleTabs, other => tab.compareTo(other));
			if (index < 0) index = -index - 1;
			this._visibleTabs.splice(index, 0, tab);
			this._visibleTabs = [...this._visibleTabs];
		}
		const newSelectedTab = tab.visible && this._selectedTab == null;
		if (newSelectedTab) {
			this._selectedTab = tab;
			tab.__setSelected(true);
		}
		this._tabSetSubscribers.forEach(callback => callback());
		if (newSelectedTab) this._selectedTabSubscribers.forEach(callback => callback(this._selectedTab));
	}

	public get allTabs(): readonly DemoTab[] {
		return this._allTabs;
	}

	public get visibleTabs(): readonly DemoTab[] {
		return this._visibleTabs;
	}

	public getTabById(id: string): DemoTab | undefined {
		return this._tabsById.get(id);
	}

	public get selectedTab(): DemoTab | null {
		return this._selectedTab;
	}

	public subscribeToTabSetChanges(callback: TabSetListener): () => void {
		this._tabSetSubscribers.push(callback);
		return () => {
			const index = this._tabSetSubscribers.indexOf(callback);
			if (index >= 0) this._tabSetSubscribers.splice(index, 1);
		};
	}

	public subscribeToSelectedTabChanges(callback: SelectedTabListener): () => void {
		this._selectedTabSubscribers.push(callback);
		return () => {
			const index = this._selectedTabSubscribers.indexOf(callback);
			if (index >= 0) this._selectedTabSubscribers.splice(index, 1);
		};
	}

	__setTabTitle(tab: DemoTab, newTitle: string): void {
		let index = binarySearch(this._allTabs, other => tab.compareTo(other));
		if (index >= 0) this._allTabs.splice(index, 1);
		if (tab.visible) {
			let index = binarySearch(this._visibleTabs, other => tab.compareTo(other));
			if (index >= 0) this._visibleTabs.splice(index, 1);
		}
		tab.__setTabTitle(newTitle);
		index = binarySearch(this._allTabs, other => tab.compareTo(other));
		if (index < 0) index = -index - 1;
		this._allTabs.splice(index, 0, tab);
		if (tab.visible) {
			let index = binarySearch(this._visibleTabs, other => tab.compareTo(other));
			if (index < 0) index = -index - 1;
			this._visibleTabs.splice(index, 0, tab);
		}
		this._tabSetSubscribers.forEach(callback => callback());
	}

	__setTabVisibility(tab: DemoTab, visible: boolean): void {
		if (visible) {
			let index = binarySearch(this._visibleTabs, other => tab.compareTo(other));
			if (index < 0) index = -index - 1;
			this._visibleTabs.splice(index, 0, tab);
		} else {
			let index = binarySearch(this._visibleTabs, other => tab.compareTo(other));
			if (index >= 0) this._visibleTabs.splice(index, 1);
			if (this._selectedTab === tab) this.__selectedTabHidden();
		}
		this._tabSetSubscribers.forEach(callback => callback());
	}

	private __selectedTabHidden(): void {
		this._selectedTab.__setSelected(false);
		this._selectedTab = this._visibleTabs.length > 0 ? this._visibleTabs[0] : null;
		if (this._selectedTab) this._selectedTab.setSelected();
		else this._selectedTabSubscribers.forEach(callback => callback(this._selectedTab));
	}

	__removeTab(tab: DemoTab): void {
		this._tabsById.delete(tab.id);
		let index = binarySearch(this._allTabs, other => tab.compareTo(other));
		if (index >= 0) this._allTabs.splice(index, 1);
		if (tab.visible) {
			let index = binarySearch(this._visibleTabs, other => tab.compareTo(other));
			if (index >= 0) this._visibleTabs.splice(index, 1);
		}
		this._tabSetSubscribers.forEach(callback => callback());
		if (this._selectedTab === tab) this.__selectedTabHidden();
	}

	__setSelectedTab(tab: DemoTab): void {
		if (this._selectedTab !== tab) {
			if (this._selectedTab) this._selectedTab.__setSelected(false);
			this._selectedTab = tab;
			this._selectedTabSubscribers.forEach(callback => callback(this._selectedTab));
		}
	}
}

export default DemoTabService;
