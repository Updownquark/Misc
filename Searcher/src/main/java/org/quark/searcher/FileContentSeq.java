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

	FileContentSeq(int bufferLength) {
		theFirstSequence = new char[bufferLength];
		theSecondSequence = new char[bufferLength];
		clear();
	}

	FileContentSeq clear() {
		thePosition = theFirstLength = theSecondLength = 0;
		theLine = theColumn = 1;
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
		} else if (length < 0) {
			roll();
			roll();
			return fill(reader);
		} else {
			boolean readAny = false;
			while (length >= theFirstLength) {
				length -= roll();
				if (fill(reader)) {
					readAny = true;
				} else {
					break;
				}
			}
			if (length > 0) {
				roll(length);
			}
			return readAny;
		}
	}

	private int roll() {
		// Discard the first sequence and roll the second into it
		int moved = theFirstLength;
		thePosition += moved;
		int lastLine = 0;
		int colPad = 0;
		for (int i = 0; i < moved; i++) {
			switch (theFirstSequence[i]) {
			case '\n':
				lastLine = i;
				theLine++;
				theColumn = 1;
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
		theColumn += moved - lastLine + colPad;
		theFirstLength = theSecondLength;
		theSecondLength = 0;
		char[] temp = theFirstSequence;
		theFirstSequence = theSecondSequence;
		theSecondSequence = temp;
		return moved;
	}

	private void roll(int length) {
		thePosition += length;
		int lastLine = 0;
		int colPad = 0;
		for (int i = 0; i < length; i++) {
			switch (theFirstSequence[i]) {
			case '\n':
				lastLine = i;
				theLine++;
				theColumn = 1;
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
		theFirstLength -= length;
		System.arraycopy(theFirstSequence, length, theFirstSequence, 0, theFirstLength);
		if (theSecondLength > 0) {
			if (theSecondLength > theFirstSequence.length - theFirstLength) {
				int move = theFirstSequence.length - theFirstLength;
				System.arraycopy(theSecondSequence, 0, theFirstSequence, theFirstLength, move);
				theFirstLength += move;
				System.arraycopy(theSecondSequence, move, theSecondSequence, 0, theSecondLength - move);
				theSecondLength -= move;
			} else {
				System.arraycopy(theSecondSequence, 0, theFirstSequence, theFirstLength, theSecondLength);
				theFirstLength += theSecondLength;
				theSecondLength = 0;
			}
		}
	}

	private boolean fill(Reader reader) throws IOException {
		int read = 0;
		boolean readAny = false;
		while (read >= 0 && theFirstLength < theFirstSequence.length) {
			theFirstLength += read;
			read = reader.read(theFirstSequence, theFirstLength, theFirstSequence.length - theFirstLength);
			readAny |= read > 0;
		}
		while (read >= 0 && theSecondLength < theSecondSequence.length) {
			theSecondLength += read;
			read = reader.read(theSecondSequence, theSecondLength, theSecondSequence.length - theSecondLength);
			readAny |= read > 0;
		}
		return readAny;
	}

	public void goToLine(Reader reader, long lineNumber) throws IOException {
		do {
			long endLine = theLine;
			if (endLine >= lineNumber) {
				return;
			}
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