package org.quark.searcher;

import java.io.IOException;
import java.io.Reader;

import org.quark.searcher.SearcherUi.SubSeq;

class FileContentSeq implements CharSequence {
	private char[] theFirstSequence;
	private char[] theSecondSequence;
	private long thePosition;
	private long theLine;
	private long theColumn;
	private int theFirstLength;
	private int theSecondLength;

	FileContentSeq(int testLength) {
		theFirstSequence = new char[testLength];
		theSecondSequence = new char[testLength];
	}

	FileContentSeq clear() {
		thePosition = theLine = theColumn = theFirstLength = theSecondLength = 0;
		return this;
	}

	int getFirstLength() {
		return theFirstLength;
	}

	long getPosition() {
		return thePosition;
	}

	public long getLine() {
		return theLine;
	}

	public long getColumn() {
		return theColumn;
	}

	boolean advance(Reader reader, int length) throws IOException {
		if (length == 0) {
			return true;
		}
		if (length < 0 || length >= theFirstLength) {
			// Discard first sequence and as much as we need to of the second sequence
			// Put the remainder of the second sequence into the first, then fill up both sequences
			thePosition += theFirstLength;
			int lastLine = 0;
			int colPad = 0;
			for (int i = 0; i < theFirstLength; i++) {
				switch (theFirstSequence[i]) {
				case '\n':
					lastLine = i;
					theLine++;
					theColumn = 0;
					colPad = -1;
					break;
				case '\r':
				case '\b':
				case '\f':
					colPad--;
					break;
				case '\t':
					colPad += 3; // Use 4-character tabs for now
					break;
				}
			}
			theColumn += theFirstLength - lastLine + colPad;

			int read;
			if (theFirstLength == 0 || length > theFirstLength) {
				if (length > 0) {
					lastLine = 0;
					colPad = 0;
					for (int i = 0; i < length - theFirstLength; i++) {
						switch (theSecondSequence[i]) {
						case '\n':
							lastLine = i;
							theLine++;
							theColumn = 0;
							colPad = -1;
							break;
						case '\r':
						case '\b':
						case '\f':
							colPad--;
							break;
						case '\t':
							colPad += 3; // Use 4-character tabs for now
							break;
						}
					}
					theColumn += length - theFirstLength - lastLine + colPad;
				}

				if (length == theFirstLength + theSecondLength) {
					theFirstLength = 0;
				} else if (length > 0) {
					int newLen = theFirstLength + theSecondLength - length;
					System.arraycopy(theSecondSequence, length - theFirstLength, theFirstSequence, 0, newLen);
					theFirstLength = newLen;
				} else {
					char[] temp = theFirstSequence;
					theFirstSequence = theSecondSequence;
					theSecondSequence = temp;
					theFirstLength = theSecondLength;
				}
				theSecondLength = 0;
				read = reader.read(theFirstSequence, theFirstLength, theFirstSequence.length - theFirstLength);
				if (read < 0) {
					return false;
				}
				while (read >= 0 && theFirstLength + read < theFirstSequence.length) {
					theFirstLength += read;
					read = reader.read(theFirstSequence, theFirstLength, theFirstSequence.length - theFirstLength);
				}
				if (read > 0) {
					theFirstLength += read;
					read = reader.read(theSecondSequence, 0, theSecondSequence.length);
					while (read >= 0 && theSecondLength + read < theSecondSequence.length) {
						theSecondLength += read;
						read = reader.read(theSecondSequence, theSecondLength, theSecondSequence.length - theSecondLength);
					}
					if (read > 0) {
						theSecondLength += read;
					}
				}
			} else {
				char[] temp = theFirstSequence;
				theFirstSequence = theSecondSequence;
				theSecondSequence = temp;
				theFirstLength = theSecondLength;
				theSecondLength = 0;
				read = reader.read(theSecondSequence);
				if (read < 0) {
					return false;
				}
				while (read >= 0 && theSecondLength + read < theSecondSequence.length) {
					theSecondLength += read;
					read = reader.read(theSecondSequence, theSecondLength, theSecondSequence.length - theSecondLength);
				}
				if (read > 0) {
					theSecondLength=read;
				}
			}
			return true;
		} else {
			// Discard the given amount of the first sequence, move the rest to the beginning of the array,
			// append content from the second sequence to it till it's full, read into the second sequence
			thePosition += length;
			int lastLine = 0;
			int colPad = 0;
			for (int i = 0; i < length; i++) {
				switch (theFirstSequence[i]) {
				case '\n':
					lastLine = i;
					theLine++;
					theColumn = 0;
					colPad = -1;
					break;
				case '\r':
				case '\b':
				case '\f':
					colPad--;
					break;
				case '\t':
					colPad += 3; // Use 4-character tabs for now
					break;
				}
			}
			theColumn += length - lastLine + colPad;

			int firstRemain = theFirstLength - length;
			System.arraycopy(theFirstSequence, length, theFirstSequence, 0, firstRemain);
			if (theSecondLength > 0) {
				int secondMoved = Math.min(theSecondLength, length);
				System.arraycopy(theSecondSequence, 0, theFirstSequence, firstRemain, secondMoved);
				theSecondLength -= secondMoved;
				System.arraycopy(theSecondSequence, secondMoved, theSecondSequence, 0, theSecondLength);
			} else {
				theFirstLength = firstRemain;
			}
			int read = reader.read(theSecondSequence, theSecondLength, theSecondSequence.length - theSecondLength);
			if (read < 0) {
				return false;
			}
			while (read >= 0 && theSecondLength + read < theSecondSequence.length) {
				theSecondLength += read;
				read = reader.read(theSecondSequence, theSecondLength, theSecondSequence.length - theSecondLength);
			}
			if (read > 0) {
				theSecondLength += read;
			}
			return true;
		}
	}

	public void goToLine(Reader reader, long lineNumber) throws IOException {
		do {
			long endLine = theLine;
			for (int i = 0; i < theFirstLength; i++) {
				if (theFirstSequence[i] == '\n') {
					endLine++;
					if (endLine == lineNumber) {
						advance(reader, i + 1);
						return;
					}
				}
			}
			advance(reader, theFirstLength == 0 ? -1 : theFirstLength);
		} while (theFirstLength > 0);
	}

	@Override
	public int length() {
		return theFirstLength + theSecondLength;
	}

	@Override
	public char charAt(int index) {
		if (index < 0) {
			throw new IndexOutOfBoundsException(index + " of " + length());
		} else if (index < theFirstLength) {
			return theFirstSequence[index];
		} else if (index < theFirstLength + theSecondLength) {
			return theSecondSequence[index - theFirstLength];
		} else {
			throw new IndexOutOfBoundsException(index + " of " + length());
		}
	}

	@Override
	public CharSequence subSequence(int start, int end) {
		return new SubSeq(this, start, end);
	}

	@Override
	public String toString() {
		StringBuilder str = new StringBuilder(theFirstLength + theSecondLength);
		str.append(theFirstSequence, 0, theFirstLength).append(theSecondSequence, 0, theSecondLength);
		return str.toString();
	}
}