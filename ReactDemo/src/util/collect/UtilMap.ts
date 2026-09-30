import MapEntry from "./MapEntry";

export default interface UtilMap<K, V>{
    readonly size: number;
    readonly keys: Iterable<K>;
    readonly values: Iterable<V>;
    readonly entries: Iterable<MapEntry<K, V>>;
    get(key: K): V | undefined;
    getEntry(key: K): MapEntry<K, V> | undefined;
    containsKey(key: K): boolean;
};
