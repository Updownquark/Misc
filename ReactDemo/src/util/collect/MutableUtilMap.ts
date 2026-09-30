import MapEntry from "./MapEntry";
import MutableMapEntry, { ReadOnlyMapEntry } from "./MutableMapEntry";
import UtilMap from "./UtilMap";

export default interface MutableUtilMap<K, V> extends UtilMap<K, V> {
	readonly entries: Iterable<MutableMapEntry<K, V>>;
	getEntry(key: K): MutableMapEntry<K, V> | undefined;
	put(key: K, value: V): V | undefined;
	with(key: K, value: V): this;
	computeIfAbsent(key: K, value: (key: K) => V): V;
	compute(key: K, value: (key: K, currentValue: V) => V): V;
	remove(key: K): V | undefined;
	clear();
	readOnly(): UtilMap<K, V>;
}

export class ReadOnlyMapWrapper<K, V> implements UtilMap<K, V> {
	private readonly _wrapped: UtilMap<K, V>;

	constructor(wrapped: UtilMap<K, V>) {
		this._wrapped = wrapped;
	}

	protected get wrapped(): UtilMap<K, V> {
		return this._wrapped;
	}

	public get size(): number {
		return this._wrapped.size;
	}
	public get keys(): Iterable<K> {
		return this._wrapped.keys;
	}
	public get values(): Iterable<V> {
		return this._wrapped.values;
	}
	public get entries(): Iterable<MapEntry<K, V>> {
        return {
            *[Symbol.iterator](): Iterator<MapEntry<K, V>>{
                for(const entry of this._wrapped.entry)
                    yield ReadOnlyMapEntry.wrap<K, V>(entry);
            }
        };
	}
	get(key: K): V {
        return this._wrapped.get(key);
	}
	getEntry(key: K): MapEntry<K, V> {
        return ReadOnlyMapEntry.wrap<K, V>(this._wrapped.getEntry(key));
	}
	containsKey(key: K): boolean {
        return this._wrapped.containsKey(key);
	}
}
