
/**
 * Searches a sorted array using binary search.
 * 
 * @param array The sorted array to search through.
 * @param compareFn A function returning negative if the thing you are searching for would be earlier than the given item,
 * 		 positive if later, or 0 if equal.
 * @returns The index of the item if found. If not found, returns `-index - 1` 
 *          where `index` is the location where the item should be inserted.
 */
export function binarySearch<T>(
	array: readonly T[],
	compareFn: (v: T) => number
): number {
	let low = 0;
	let high = array.length - 1;

	while (low <= high) {
		// Fast bitwise math floor for finding the midpoint
		const mid = (low + high) >>> 1;
		const comparison = compareFn(array[mid]);

		if (comparison > 0) {
			low = mid + 1; // Target is in the right half
		} else if (comparison < 0) {
			high = mid - 1; // Target is in the left half
		} else {
			return mid; // Found the item!
		}
	}

	// Not found. 'low' is the exact insertion index.
	return -low - 1;
}

const naturalCollator = new Intl.Collator(undefined, {
	numeric: true,
	sensitivity: 'base', // Optional: ignores case and accents (e.g., 'a' === 'A' === 'á')
});

export function compareNumberTolerant(a: string, b: string): number {
	return naturalCollator.compare(a, b);
}
