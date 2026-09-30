import MapEntry from "./MapEntry";
import MutableMapEntry, { ReadOnlyMapEntry } from "./MutableMapEntry";
import MutableUtilMap, { ReadOnlyMapWrapper } from "./MutableUtilMap";
import SortedUtilMap, { Comparator } from "./SortedUtilMap";

export default interface MutableSortedUtilMap<K, V> extends SortedUtilMap<K, V>, MutableUtilMap<K, V> {
	getEntry(key: K): MutableMapEntry<K, V> | undefined;
	readonly entries: Iterable<MutableMapEntry<K, V>>;
	entryAt(index: number): MutableMapEntry<K, V>;
	removeAt(index: number): MutableMapEntry<K, V>;
	readOnly(): SortedUtilMap<K, V>;
}

export class ReadOnlySortedMapWrapper<K, V> extends ReadOnlyMapWrapper<K, V> implements SortedUtilMap<K, V> {
	constructor(wrapped: SortedUtilMap<K, V>) {
		super(wrapped);
	}

	protected get wrapped(): SortedUtilMap<K, V> {
		return super.wrapped as SortedUtilMap<K, V>;
	}

	public get comparator(): Comparator<K> {
		return this.wrapped.comparator;
	}

	indexOf(key: K): number {
		return this.wrapped.indexOf(key);
	}
	entryAt(index: number): MapEntry<K, V> {
		return ReadOnlyMapEntry.wrap(this.wrapped.entryAt(index));
	}
	removeAt(index: number): MapEntry<K, V> {
		return ReadOnlyMapEntry.wrap(this.wrapped.removeAt(index));
	}
}
