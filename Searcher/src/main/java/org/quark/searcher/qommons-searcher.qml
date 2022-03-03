<?xml version="1.0" encoding="UTF-8"?>

<quick-debug
	uses:swing="../../../../../../../ObServe/target/classes/org/observe/util/swing/quick-swing.qtd"
	uses:base="../../../../../../../ObServe/target/classes/org/observe/util/swing/quick-base.qtd"
	with-extension="swing:quick,window"
	look-and-feel="system" title="Qommons Searcher"
	x="config.x" y="config.y" width="config.width" height="config.height" close-action="exit"
	debug-visible="true">
	<head>
		<imports>
			<import>org.quark.searcher.QuickSearcher</import>
			<import>org.quark.searcher.PatternConfig</import>
			<import>org.qommons.io.BetterFile</import>
		</imports>
		<models>
			<ext-model name="ext">
				<ext-value name="workingDir" type="String" />
				<ext-action name="searchAction" type="Void" />
				<ext-value name="resultRoot" type="QuickSearcher.SearchResultNode" />
				<ext-value name="status" type="QuickSearcher.SearchStatus" />
				<ext-value name="statusText" type="String" />
			</ext-model>
			<config name="config" config-name="qommons-searcher">
				<value name="zipLevel" type="int" default="10" config-path="zip-level" />
				<file-source name="files" max-archive-depth="config.zipLevel">
					<archive-method type="zip" />
					<archive-method type="tar" />
					<archive-method type="gz" />
				</file-source>
				<format name="fileFormat" type="file" file-source="files" working-dir="ext.workingDir" />
				<format name="patternFormat" type="regex-format" />
				<format name="byteFormat" type="double" sig-digs="4" unit="b" metric-prefixes-p2="true" />
				<format name="timeFormat" type="instant" max-resolution="Minute" relative-eval-type="Past" />
				<simple-config-format name="fileReqFormat" type="org.quark.searcher.FileAttributeRequirement" default="Maybe" />

				<value name="x" type="int" />
				<value name="y" type="int" />
				<value name="width" type="int" />
				<value name="height" type="int" />
				<value name="searchBase" type="BetterFile" format="fileFormat" default="." />
				<value name="fileNamePattern" type="java.util.regex.Pattern" format="patternFormat" />
				<value name="fileNameCaseSensitive" type="boolean" default="false" />
				<value name="fileTextPattern" type="java.util.regex.Pattern" format="patternFormat" />
				<value name="fileTextCaseSensitive" type="boolean" default="false" />
				<value name="multiContentMatches" type="boolean" default="true" />
				<value name="maxFileMatchLength" type="int" default="10000" />
				<value-set name="excludedFileNames" type="org.quark.searcher.PatternConfig" />
				<map name="fileRequirements" key-type="org.qommons.io.BetterFile.FileBooleanAttribute"
					type="org.quark.searcher.FileAttributeRequirement" format="fileReqFormat" />
				<value name="minSize" type="double" default="0" />
				<value name="maxSize" type="double" default="${QuickSearcher.DEFAULT_MAX_SIZE}" />
				<value name="minLM" type="java.time.Instant" default="Jan 01 1900 12:00am" />
				<value name="maxLM" type="java.time.Instant" default="Jan 01 3000 12:00am" />
				<!-- TODO Filter accept the mins/maxes above to keep max>min -->

				<value name="mainSplitDiv" type="double" />
				<value name="rightSplitDiv" type="double" />
			</config>
			<model name="app">
				<transform name="configurable" source="ext.status">
					<map-to function="QuickSearcher.isConfigurable" />
				</transform>
				<transform name="searchText" source="ext.status">
					<map-to function="QuickSearcher::getSearchText" />
				</transform>
				<value name="selectedResult" type="QuickSearcher.SearchResultNode" />
				<transform name="textMatches" source="selectedResult">
					<flatten function="QuickSearcher.SearchResultNode::getTextResults" null-to-null="true" />
				</transform>
				<value name="selectedTextMatch" type="QuickSearcher.TextResult" />
				<transform name="selectedText" source="selectedTextMatch">
					<map-to function="QuickSearcher::renderTextResult" />
				</transform>
			</model>
			<model name="junk">
				<value name="_testFilePath" type="BetterFile" />
				<transform name="testFilePath" source="_testFilePath">
					<refresh on="config.fileNamePattern" />
				</transform>
				<value name="_testFileContent" type="String" />
				<transform name="testFileContent" source="_testFileContent">
					<refresh on="config.fileTextPattern" />
				</transform>
				<format name="fileNamePatternFormat" type="file" file-source="config.files" working-dir="ext.workingDir">
					<validate type="regex-validation" pattern="config.fileNamePattern" />
				</format>
				<format name="fileContentPatternFormat" type="text">
					<validate type="regex-validation" pattern="${config.fileTextPattern}" />
				</format>
			</model>
		</models>
	</head>
	<box layout="inline" orientation="vertical" main-align="justify" cross-align="justify">
		<split orientation="horizontal" split-position="${config.mainSplitDiv}">
			<field-panel>
				<box layout="inline" orientation="horizontal" main-align="justify" field-name="Search In:" fill="true">
					<text-field value="config.searchBase" format="config.fileFormat" disable-with="app.configurable" />
					<file-button open="true" value="config.searchBase" disable-with="app.configurable" />
				</box>
				<spacer length="3" />
				<label fill="true">----File Name----</label>
				<box field-name="File Pattern:" layout="border" fill="true">
					<text-field value="config.fileNamePattern" format="config.patternFormat" disable-with="app.configurable" />
					<check-box region="east" value="config.fileNameCaseSensitive" disable-with="app.configurable">Case:</check-box>
				</box>
				<box layout="inline" orientation="horizontal" main-align="justify" field-name="Test File:" fill="true">
					<text-field value="junk.testFilePath" format="junk.fileNamePatternFormat" disable-with="app.configurable" />
					<file-button open="true" value="junk.testFilePath" disable-with="app.configurable" />
				</box>
				<spacer length="3" />
				<label fill="true">----File Content----</label>
				<box field-name="Text Pattern:" layout="border" fill="true">
					<text-field value="config.fileTextPattern" format="config.patternFormat" disable-with="app.configurable" />
					<check-box region="east" value="config.fileTextCaseSensitive" disable-with="app.configurable">Case:</check-box>
				</box>
				<text-field field-name="Test Content:" value="junk.testFileContent" format="junk.fileContentPatternFormat" fill="true" />
				<check-box value="config.multiContentMatches" field-name="Multiple Text Matches:" disable-with="app.configurable" />
				<spacer length="3" />
				<label fill="true">Excluded File Names</label>
				<table rows="config.excludedFileNames" fill="true" value-name="row" render-value-name="col">
					<column name="Pattern" value="row.getPattern()">
						<column-edit type="modify-row-value" function="PatternConfig::setPattern">
							<text-field format="config.patternFormat" />
						</column-edit>
					</column>
					<column name="Case" value="row.isCaseSensitive()">
						<column-edit type="modify-row-value" function="PatternConfig::setCaseSensitive">
							<check-box />
						</column-edit>
					</column>
				</table>
				<spacer length="3" />
				<label fill="true">----File Metadata----</label>
				<text-field field-name="Max Archive Depth:" value="config.zipLevel" disable-with="app.configurable" columns="8" />
				<radio-buttons field-name="Directory:" render-value-name="type" value="config.fileRequirements.observe(Directory)"
					values="org.quark.searcher.FileAttributeRequirement.values()" disable-with="app.configurable" />
				<radio-buttons field-name="Readable:" render-value-name="type" value="config.fileRequirements.observe(Readable)"
					values="org.quark.searcher.FileAttributeRequirement.values()" disable-with="app.configurable" />
				<radio-buttons field-name="Writable:" render-value-name="type" value="config.fileRequirements.observe(Writable)"
					values="org.quark.searcher.FileAttributeRequirement.values()" disable-with="app.configurable" />
				<radio-buttons field-name="Hidden:" render-value-name="type" value="config.fileRequirements.observe(Hidden)"
					values="org.quark.searcher.FileAttributeRequirement.values()" disable-with="app.configurable" />
				<box field-name="Size:" layout="inline" orientation="horizontal" main-align="justify" fill="true">
					<text-field value="config.minSize" format="config.byteFormat" disable-with="app.configurable" />
					<label>...</label>
					<text-field value="config.maxSize" format="config.byteFormat" disable-with="app.configurable" />
				</box>
				<box field-name="Last Modified:" layout="inline" orientation="horizontal" main-align="justify" fill="true">
					<text-field value="config.minLM" format="config.timeFormat" disable-with="app.configurable" />
					<label>...</label>
					<text-field value="config.maxLM" format="config.timeFormat" disable-with="app.configurable" />
				</box>
				<box layout="inline" orientation="horizontal" main-align="center" fill="true">
					<button action="ext.searchAction" text="app.searchText" />
				</box>
			</field-panel>
			<split orientation="vertical" split-position="${config.rightSplitDiv}">
				<tree root="ext.resultRoot" value-name="path" render-value-name="node" children="node.getChildren()"
					selection="app.selectedResult">
					<column name="Tree" format="node.getFile().getName()" />
				</tree>
				<box layout="inline" orientation="vertical" main-align="justify" cross-align="justify">
					<table rows="app.textMatches" selection="app.selectedTextMatch" value-name="row" render-value-name="col">
						<column name="Value" value="row.getValue()" />
						<column name="Pos" value="row.getPosition()" />
						<column name="Line" value="row.getLineNumber()" />
						<column name="Col" value="row.getColumnNumber()" />
					</table>
					<text-area rows="10" value="app.selectedText" html="true" editable="false" />
				</box>
			</split>
		</split>
		<label value="ext.statusText" />
	</box>
</quick-debug>
