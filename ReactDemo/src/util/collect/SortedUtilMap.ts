import MapEntry from "./MapEntry";
import UtilMap from "./UtilMap";

export type Comparator<T> = (v1: T, v2: T)=> number;

export default interface SortedUtilMap<K, V> extends UtilMap<K, V>{
    readonly comparator: Comparator<K>;
    indexOf(key: K): number;
    entryAt(index: number): MapEntry<K, V>;
    removeAt(index: number): MapEntry<K, V>
}