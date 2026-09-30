import MapEntry from "./MapEntry";

export default interface MutableMapEntry<K, V> extends MapEntry<K, V>{
    value: V;
    remove(): boolean;
}

export class ReadOnlyMapEntry<K, V> implements MapEntry<K, V>{
    private readonly _wrapped: MapEntry<K, V>;

    constructor(wrapped: MapEntry<K, V>){
        this._wrapped=wrapped;
    }

    public get key(): K{
        return this._wrapped.key;
    }
    public get value(): V{
        return this._wrapped.value;
    }
    isPresent(): boolean {
        return this._wrapped.isPresent();
    }
    matchesKey(key: K): boolean {
        return this._wrapped.matchesKey(key);
    }

    public static wrap<K, V>(entry?: MapEntry<K, V>): MapEntry<K, V> | undefined{
        if(!entry)
            return undefined;
        else if(typeof entry["remove"] === "function" )
            return new ReadOnlyMapEntry<K, V>(entry);
        else
            return entry;
    }
}
