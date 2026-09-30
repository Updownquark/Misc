import { MutableMapEntry } from "./MutableMapEntry";
import MutableUtilMap, { ReadOnlyMapWrapper } from "./MutableUtilMap";
import UtilMap from "./UtilMap";

export interface Hashable {
	hashCode(): string | number;
	equals(other: unknown): boolean;
}

class HashMapEntry<K extends Hashable, V> implements MutableMapEntry<K, V>{
	public readonly key: K;
	public readonly hashCode: string | number;
	public value: V;
    private _map: HashMap<K, V>;

	constructor(key: K, value: V, map: HashMap<K, V>) {
		this.key = key;
		this.hashCode = key?.hashCode() ?? 0;
		this.value = value;
        this._map=map;
	}

    public isPresent(): boolean{
        return this._map? true : false;
    }

    public matchesKey(key: K) : boolean{
        if(key)
            return this.key && this.key.equals(key);
        else
            return this.key ? false : true;
    }

    public remove(): boolean{
        if(this._map){
            const removed=this._map._remove(this.hashCode, this.key);
            this._map=null;
			return Boolean(removed);
        } else
			return false;
    }

    __removedInternalOnlyDoNotCall(){
        this._map=null;
    }
}

export type HashEntryVisitor<K extends Hashable, V> = (entry: MutableMapEntry<K, V>) => boolean;

/**
 * A hash map to allow storage of objects that implement the Hashable interface.
 * Normal Maps only operate on the identity of the object,
 * so it's not possible to use them on keys with the concept of "funcional" equality.
 */
class HashMap<K extends Hashable, V> implements MutableUtilMap<K, V>{
	// Buckets store arrays of pairs to handle collisions
	private buckets = new Map<string | number, HashMapEntry<K, V>[]>();
	private _size = 0;

	public get size(): number {
		return this._size;
	}

	private *_entryIterator(): IterableIterator<HashMapEntry<K, V>>{
		for(const bucket of this.buckets.values()){ //JavaScript maps handle live modifications gracefully
            //JavaScript arrays don't handle live modifications gracefully
            for(let i=0;i<bucket.length;){
				const entry=bucket[i];
				yield entry;
				if(entry.isPresent()){
					//Only increment if the entry is still present.
					//If the user removed it, bucket[i] is now the next entry to yield.
					i++;
				}
			}
		}
	}

	public get keys(): Iterable<K>{
		const entryIterGen = this._entryIterator();
		return {
			[Symbol.iterator]: function* (){
				for(const entry of entryIterGen){
					yield entry.key;
				}
			}
		};
	}

	public get values(): Iterable<V>{
		const entryIterGen = this._entryIterator();
		return {
			[Symbol.iterator]: function* (){
				for(const entry of entryIterGen){
					yield entry.value;
				}
			}
		};
	}

	public get entries(): Iterable<MutableMapEntry<K, V>>{
		const entryIterGen = this._entryIterator();
		return {
			[Symbol.iterator]: function* (){
				for(const entry of entryIterGen){
					yield entry;
				}
			}
		};
	}

	public put(key: K, value: V): V | undefined {
		const hash = key?.hashCode() ?? 0;
		let bucket = this.buckets.get(hash);
		if (!bucket) {
			bucket = [];
			this.buckets.set(hash, bucket);
		}

		const entry = bucket.find(item => item.matchesKey(key));

		let prev: V;
		if (entry) {
			prev = entry.value;
			entry.value = value; // Update existing key
		} else {
			prev = undefined;
			bucket.push(new HashMapEntry<K, V>(key, value, this)); // Append new key due to collision or new hash
			this._size++;
		}
		return prev;
	}

	public with(key: K, value: V): this {
		this.put(key, value);
		return this;
	}

	public computeIfAbsent(key: K, value: (key: K) => V): V {
		const hash = key?.hashCode() ?? 0;
		let bucket = this.buckets.get(hash);
		let entry: HashMapEntry<K, V>;
		if (!bucket) {
			const newValue=value(key);
			if(newValue){
				entry=new HashMapEntry<K, V>(key, newValue, this);
			} else
				return null;
			bucket = [];
			this.buckets.set(hash, bucket);
		} else{
			entry = bucket.find(item => item.matchesKey(key));
			if (entry) return entry.value;
			entry = new HashMapEntry<K, V>(key, value(key), this);
		}

		bucket.push(entry);
		this._size++;
		return entry.value;
	}

	public compute(key: K, value: (key: K, currentValue: V)=>V): V{
		const hash = key?.hashCode() ?? 0;
		let bucket = this.buckets.get(hash);
		let entry: HashMapEntry<K, V>;
		let newValue: V;
		if (!bucket) {
			newValue=value(key, undefined);
			if(newValue){
				entry=new HashMapEntry<K, V>(key, newValue, this);
			} else
				return undefined;
			bucket = [];
			this.buckets.set(hash, bucket);
			bucket.push(entry);
			this._size++;
		} else{
			entry = bucket.find(item => item.matchesKey(key));
			newValue=value(key, entry?.value);
			if (entry){
				if(newValue){
					entry.value=newValue;
				}else{
					entry.remove();
					return undefined;
				}
			} else if(newValue){
				entry = new HashMapEntry<K, V>(key, newValue, this);
				bucket.push(entry);
				this._size++;
			} else
				return null;
		}

		return newValue;
	}

	public get(key: K): V | undefined {
        return this.getEntry(key)?.value;
	}

    public getEntry(key: K): MutableMapEntry<K, V> | undefined {
		const bucket = this.buckets.get(key?.hashCode() ?? 0);
		if (!bucket) return undefined;

		return bucket.find(item => item.matchesKey(key));
    }

	public containsKey(key: K): boolean {
        return this.getEntry(key) ? true : false;
	}

	public remove(key: K): V | undefined {
        return this._remove(key?.hashCode() ?? 0, key)?.value;
    }

    _remove(hash: number | string, key: K): HashMapEntry<K, V> | undefined{
		const bucket = this.buckets.get(hash);
		if (!bucket) return undefined;

        if(bucket.length==1){
            if(bucket[0].matchesKey(key)){
                bucket[0].__removedInternalOnlyDoNotCall();
                this.buckets.delete(hash);
        		this._size--;
                return bucket[0];
            } else
                return undefined;
        }
		const index = bucket.findIndex(item => item.key.equals(key));
		if (index === -1) return undefined;

		const entry = bucket[index];
        bucket[index].__removedInternalOnlyDoNotCall();
		bucket.splice(index, 1);
		this._size--;
		return entry;
	}

    public clear(){
        this.buckets.clear();
        this._size=0;
    }

	public readOnly(): UtilMap<K, V>{
		return new ReadOnlyMapWrapper<K, V>(this);
	}
}

export default HashMap;
