import MutableMapEntry from "./MutableMapEntry";
import MutableUtilMap from "./MutableUtilMap";
import * as Utils from "../Utils";
import SortedUtilMap from "./SortedUtilMap";
import { ReadOnlySortedMapWrapper } from "./MutableSortedUtilMap";

export type Comparator<T> = (v1: T, v2: T)=> number;

class ArrayMapEntry<K, V> implements MutableMapEntry<K, V>{
    private _map: SortedArrayMap<K, V>;
    public readonly key: K;
    private _value: V;

    constructor(map: SortedArrayMap<K, V>, key: K, value: V){
        this._map=map;
        this.key=key;
        this._value=value;
    }

    public get value(): V{
        return this._value;
    }

    public set value(value: V){
        this._value=value;
    }

    isPresent(): boolean {
        return this._map && this._map.getEntry(this.key)==this;
    }

    matchesKey(key: K): boolean {
        return this._map && this._map.comparator(this.key, key)==0;
    }

    remove(): boolean {
        if(!this._map)
            return false;
        const index=this._map.indexOf(this.key);
        if(index>=0 && this._map.entryAt(index)==this){
            this._map.removeAt(index);
            return true;
        }
        return false;
    }
}

export default class SortedArrayMap<K, V> implements MutableUtilMap<K, V>{
    public readonly comparator: Comparator<K>;
    private readonly _entries: ArrayMapEntry<K, V>[]=[];

    constructor(comparator: Comparator<K>){
        this.comparator=comparator;
    }

    public get size(): number{
        return this._entries.length;
    }

    public get keys(): Iterable<K>{
        const entries=this._entries;
        return {
            [Symbol.iterator]: function*(){
                for(let i=0;i<entries.length;){
                    const entry=entries[i];
                    yield entry.key;
                    if(entries[i]==entry)
                        i++; //Otherwise the entry was removed by the caller
                }
            }
        };
    }
    public get values(): Iterable<V>{
        const entries=this._entries;
        return {
            [Symbol.iterator]: function*(){
                for(let i=0;i<entries.length;){
                    const entry=entries[i];
                    yield entry.value;
                    if(entries[i]==entry)
                        i++; //Otherwise the entry was removed by the caller
                }
            }
        };
    }

    public get entries(): Iterable<MutableMapEntry<K, V>>{
        const entries=this._entries;
        return {
            [Symbol.iterator]: function*(){
                for(let i=0;i<entries.length;){
                    const entry=entries[i];
                    yield entry;
                    if(entries[i]==entry)
                        i++; //Otherwise the entry was removed by the caller
                }
            }
        };
    }

    get(key: K): V {
        return this.getEntry(key)?.value;
    }
    getEntry(key: K): MutableMapEntry<K, V> {
        const index=Utils.binarySearch(this._entries, entry=>this.comparator(key, entry.key));
        return index<0 ? undefined : this._entries[index];
    }
    containsKey(key: K): boolean {
        return Boolean(this.getEntry(key));
    }
    indexOf(key: K): number{
        const index=Utils.binarySearch(this._entries, entry=>this.comparator(key, entry.key));
        return index<0 ? -1 : index;
    }
    entryAt(index: number): MutableMapEntry<K, V>{
        return this._entries[index];
    }
    put(key: K, value: V): V {
        const index=Utils.binarySearch(this._entries, entry=>this.comparator(key, entry.key));
        if(index>=0){
            const entry=this._entries[index];
            const prevV=entry.value;
            entry.value=value;
            return prevV;
        }
        this._entries.splice(-index-1, 0, new ArrayMapEntry<K, V>(this, key, value))
        return undefined;
    }
    with(key: K, value: V): this {
        this.put(key, value);
        return this;
    }
    computeIfAbsent(key: K, value: (key: K) => V): V {
        const index=Utils.binarySearch(this._entries, entry=>this.comparator(key, entry.key));
        if(index>=0)
            return this._entries[index].value;
        const newValue=value(key);
        this._entries.splice(-index-1, 0, new ArrayMapEntry<K, V>(this, key, newValue))
        return newValue;
    }
    compute(key: K, value: (key: K, currentValue: V)=>V): V{
        const index=Utils.binarySearch(this._entries, entry=>this.comparator(key, entry.key));
        let newValue: V;
        if(index<0){
            newValue=value(key, undefined);
            if(newValue)
                this._entries.splice(-index-1, 0, new ArrayMapEntry<K, V>(this, key, newValue));
        } else{
            const entry=this._entries[index];
            newValue=value(key, entry.value);
            if(newValue)
                entry.value=newValue;
            else
                this._entries.splice(index, 1);
        }
        return newValue;
    }
    remove(key: K): V {
        const index=Utils.binarySearch(this._entries, entry=>this.comparator(key, entry.key));
        if(index>=0){
            const entry=this._entries[index];
            this._entries.splice(index, 1);
            return entry.value;
        }
        return undefined;
    }
    removeAt(index: number): MutableMapEntry<K, V>{
        const entry=this._entries[index];
        this._entries.splice(index, 1);
        return entry;
    }
    clear() {
        this._entries.length=0;
    }

    readOnly(): SortedUtilMap<K, V> {
        return new ReadOnlySortedMapWrapper<K, V>(this);
    }
}
