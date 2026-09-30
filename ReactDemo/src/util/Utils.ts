/**
 * Searches a sorted array using binary search.
 *
 * @param array The sorted array to search through.
 * @param compareFn A function returning negative if the thing you are searching for would be earlier than the given item,
 * 		 positive if later, or 0 if equal.
 * @param onFind If unspecified, the return index may be positive, indicating the index at which a matching item was found.
 * 		Otherwise, this function continues to search for the boundary between matching items
 * 		and earlier (if onFind==true) or later (if onFind==false) items.
 * 		This is useful for inserting items into an array where the sorting may not be distinct.
 * @returns The index of the item if found. If not found, returns `-index - 1`
 *          where `index` is the location where the item should be inserted.
 */
export function binarySearch<T>(array: readonly T[], compareFn: (v: T) => number, onFind?: boolean): number {
	let low = 0;
	let high = array.length - 1;

	let found = -1;
	while (low <= high) {
		// Fast bitwise math floor for finding the midpoint
		const mid = (low + high) >>> 1;
		const comparison = compareFn(array[mid]);

		if (comparison > 0) {
			low = mid + 1; // Target is in the right half
		} else if (comparison < 0) {
			high = mid - 1; // Target is in the left half
		} else {
			// Found the item!
			if (typeof onFind !== "boolean") return mid; //We're done
			found = mid;
			if (onFind) high = mid - 1;
			else low = mid + 1;
		}
	}

	if (found >= 0) return -found - 1;
	// Not found. 'low' is the exact insertion index.
	return -low - 1;
}

const naturalCollator = new Intl.Collator(undefined, {
	numeric: true,
	sensitivity: "variant",
	caseFirst: "upper",
});

export function compareNumberTolerant(a: string, b: string): number {
	return naturalCollator.compare(a, b);
}

export function hashString(str: string) {
	if (!str) return 0;
	let h = 0;
	for (let i = 0; i < str.length; i++) {
		h = 31 * h + str.charCodeAt(i);
	}
	return h;
}

export function hashArray<T>(array: readonly T[], valueHasher: (value: T) => number): number {
	if (!array || array.length == 0) return 0;
	let h = 0;
	for (let i = 0; i < array.length; i++) {
		h = h * 31 + valueHasher(array[i]);
	}
	return h;
}

export function arraysEqual<T>(
	array1: readonly T[],
	array2: readonly T[],
	equality: (value1: T, value2: T) => boolean,
): boolean {
	if (array1 == array1) return true;
	else if (!array1 || !array2) return false;
	else if (array1.length != array2.length) return false;
	for (let i = 0; i < array1.length; i++) {
		if (!equality(array1[i], array2[i])) return false;
	}
	return true;
}

export function randomIntId() {
	const array = new BigUint64Array(1);
	crypto.getRandomValues(array);
	const randomId = BigInt(array[0]) % BigInt(Number.MAX_SAFE_INTEGER + 1);
	return Number(randomId);
}

export function groupBy<K, V>(values: Iterable<V>, key: (value: V) => K): Map<K, V[]> {
	const map = new Map<K, V[]>();
	for (const value of values) {
		const vk = key(value);
		let group = map.get(vk);
		if (!group) {
			group = [];
			map.set(vk, group);
		}
		group.push(value);
	}
	return map;
}

export function hash(arr: readonly any[]): string {
  const str = JSON.stringify(arr);
  let h1 = 0xdeadbeef, h2 = 0x41c6ce57;
  
  for (let i = 0; i < str.length; i++) {
    const code = str.charCodeAt(i);
    h1 = Math.imul(h1 ^ code, 2654435761);
    h2 = Math.imul(h2 ^ code, 1597334677);
  }
  
  h1 = Math.imul(h1 ^ (h1 >>> 16), 2246822507) ^ Math.imul(h2 ^ (h2 >>> 16), 3266489909);
  h2 = Math.imul(h2 ^ (h2 >>> 16), 2246822507) ^ Math.imul(h1 ^ (h1 >>> 16), 3266489909);
  
  const pad = (n) => (n >>> 0).toString(16).padStart(8, '0');
  
  return pad(h1) + pad(h2) + pad(h1 ^ h2) + pad((h1 + h2) >>> 0);
}
