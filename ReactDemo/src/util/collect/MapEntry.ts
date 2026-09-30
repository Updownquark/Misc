
export default interface MapEntry<K, V>{
    readonly key: K;
    readonly value: V;
    isPresent(): boolean;
    matchesKey(key: K): boolean;
}
