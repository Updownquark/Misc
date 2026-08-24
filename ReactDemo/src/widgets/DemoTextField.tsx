import { useState, useEffect } from "react";
import { TextField } from "@mui/material";

interface TextFieldProps<T> {
	value: T;
	onChange: (value: T) => void;
	parser: (value: string) => T | null;
	renderer?: (value: T) => string;
	validator?: (value: T) => string | null;
}

function render<T>(value: T, renderer?: (value: T) => string): string {
	if (renderer) {
		return renderer(value);
	} else {
		return String(value);
	}
}

function DemoTextField<T>({ value, onChange, parser, renderer, validator }: TextFieldProps<T>) {
	const [draftValue, setDraftValue] = useState(value);
	const [draftText, setDraftText] = useState(render(value, renderer));
	const [isDirty, setIsDirty] = useState(false);
	const [isValid, setValid] = useState(null);

	useEffect(() => {
		setDraftValue(value);
		setDraftText(render(value, renderer));
		setIsDirty(false);
		setValid(null);
	}, []);

	const commit = () => {
		if (isDirty && isValid == null) {
			onChange(draftValue);
			setIsDirty(false);
		}
	};

	const revert = () => {
		if (isDirty) {
			setDraftValue(value);
			setDraftText(render(value, renderer));
			setIsDirty(false);
			setValid(null);
		}
	};

	const unfocus = () => {
		if (isDirty) {
			if (isValid == null) commit();
			else revert();
		}
	};

	const handleInputChange = (e: React.ChangeEvent<HTMLInputElement>) => {
		const newValue = e.target.value;
        setDraftText(newValue);
		var parsedValue: T | null;
		try {
			parsedValue = parser(newValue);
		} catch {
			setValid("Invalid input");
			return;
		}
		setDraftValue(parsedValue);
		setIsDirty(true);
		if (validator) {
			setValid(validator(parsedValue));
		}
	};

	return (
		<TextField
			value={draftText}
			onChange={handleInputChange}
			onBlur={unfocus}
			onKeyDown={e => {
				if (e.key === "Enter") {
					commit();
				} else if (e.key === "Escape") {
					revert();
				}
			}}
			error={isValid != null}
			helperText={isValid}
		/>
	);
}

export default DemoTextField;
