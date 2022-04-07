<?xml version="1.0" encoding="UTF-8"?>

<quick
	uses:swing="../../../../../../../ObServe/target/classes/org/observe/quick/quick-swing.qtd"
	uses:base="../../../../../../../ObServe/target/classes/org/observe/quick/quick-base.qtd"
	uses:ext="../../../../../../../ObServe/target/classes/org/observe/quick/quick-ext.qtd"
	with-extension="swing:quick,window"
	look-and-feel="system" title="Qommons Searcher"
	x="config.x" y="config.y" width="config.width" height="config.height" close-action="exit">
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
			<config name="config" config-name="qommons-search">
				<value name="zipLevel" type="int" default="10" config-path="zip-level" />
				<file-source name="files" max-archive-depth="config.zipLevel">
					<archive-method type="zip" />
					<archive-method type="tar" />
					<archive-method type="gz" />
				</file-source>
				<format name="fileFormat" type="file" file-source="files" working-dir="ext.workingDir" />
				<format name="patternFormat" type="regex-format-string" />
				<format name="byteFormat" type="double" sig-digs="4" unit="b" metric-prefixes-p2="true" />
				<format name="timeFormat" type="instant" max-resolution="Minute" relative-eval-type="Past" />
				<simple-config-format name="fileReqFormat" type="org.quark.searcher.FileAttributeRequirement" default="Maybe" />

				<value name="x" type="int" />
				<value name="y" type="int" />
				<value name="width" type="int" />
				<value name="height" type="int" />
				<value name="searchBase" type="BetterFile" format="fileFormat" default="." />
				<value name="fileNamePattern" type="String" format="patternFormat" />
				<value name="fileNameRegex" type="boolean" default="true" />
				<value name="fileNameCaseSensitive" type="boolean" default="false" />
				<value name="fileTextPattern" type="String" format="patternFormat" />
				<value name="fileTextRegex" type="boolean" default="true" />
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

				<value name="mainSplitDiv" type="double" default="25" />
				<value name="rightSplitDiv" type="double" default="40" />
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
		</models>
	</head>
	<box layout="inline" orientation="vertical" main-align="justify" cross-align="justify">
		<split orientation="horizontal" split-position="${config.mainSplitDiv + &quot;%&quot;}">
			<field-panel>
				<box layout="inline" orientation="horizontal" main-align="justify" field-name="Search In:" fill="true">
					<text-field value="config.searchBase" format="config.fileFormat" disable-with="app.configurable" columns="100"
						tooltip="Root folder or file to search in" />
					<file-button open="true" value="config.searchBase" disable-with="app.configurable"
						tooltip="Root folder or file to search in" />
				</box>
				<spacer length="3" />
				<box layout="inline" orientation="horizontal" main-align="center" fill="true">
					<label>----File Name----</label>
				</box>
				<box field-name="File Pattern:" layout="inline" orientation="horizontal" main-align="justify" fill="true">
					<text-field value="config.fileNamePattern" format="config.patternFormat" disable-with="app.configurable"
						commit-on-type="true" tooltip="Pattern of file names to search for" />
					<check-box value="config.fileNameRegex" disable-with="app.configurable"
						tooltip="Whether the file pattern is evaluated as a regular expression">Regex:</check-box>
					<check-box value="config.fileNameCaseSensitive" disable-with="app.configurable"
						tooltip="Whether the file pattern is evaluated case-sensitively">Case:</check-box>
				</box>
				<box layout="inline" orientation="horizontal" main-align="justify" field-name="Test File:" fill="true"
					visible="config.fileNamePattern!=null &amp; !config.fileNamePattern.isEmpty() &amp; config.fileNameRegex">
					<model>
						<value name="_testFilePath" type="BetterFile" />
						<transform name="testFilePath" source="_testFilePath">
							<refresh on="config.fileNamePattern" />
						</transform>
						<format name="fileNamePatternFormat" type="file" file-source="config.files" working-dir="ext.workingDir">
							<validate type="regex-validation" pattern="config.fileNamePattern" />
						</format>
					</model>
					<text-field value="testFilePath" format="fileNamePatternFormat" disable-with="app.configurable"
						commit-on-type="true" tooltip="Enter a file name to test the file pattern against it" />
					<file-button open="true" value="testFilePath" disable-with="app.configurable"
						tooltip="Enter a file name to test the file pattern against it" />
				</box>
				<spacer length="3" />
				<box layout="inline" orientation="horizontal" main-align="center" fill="true">
					<label>----File Content----</label>
				</box>
				<box field-name="Text Pattern:" layout="inline" orientation="horizontal" main-align="justify" fill="true">
					<model>
						<first-value name="textPatternModEnabled">
							<value>app.configurable</value>
							<value>config.fileTextPattern==null || config.fileTextPattern.isEmpty() ? &quot;No Text Pattern set&quot;"</value>
						</first-value>
					</model>
					<text-field value="config.fileTextPattern" format="config.patternFormat" disable-with="app.configurable"
						commit-on-type="true" tooltip="Text to search for in matching files" />
					<check-box value="config.fileTextRegex" disable-with="textPatternModEnabled"
						tooltip="Whether the file content pattern is evaluated as a regular expression">Regex:</check-box>
					<check-box value="config.fileTextCaseSensitive" disable-with="textPatternModEnabled"
						tooltip="Whether the text pattern is evaluated case-sensitively">Case:</check-box>
				</box>
				<box field-name="Test Text:" layout="border" fill="true"
					visible="config.fileTextPattern!=null &amp; !config.fileTextPattern.isEmpty() &amp; config.fileTextRegex">
					<model>
						<value name="_testFileContent" type="String" />
						<transform name="testFileContent" source="_testFileContent">
							<refresh on="config.fileTextPattern" />
						</transform>
						<format name="fileContentPatternFormat" type="text">
							<validate type="regex-validation" pattern="${config.fileTextPattern}" />
						</format>
					</model>
					<text-field value="testFileContent" format="fileContentPatternFormat"
						commit-on-type="true" tooltip="Enter text to test the text pattern against it" />
				</box>
				<check-box value="config.multiContentMatches" field-name="Multiple Text Matches:" disable-with="app.configurable" />
				<spacer length="3" />
				<collapse-pane fill="true" name="debug">
					<box role="header" layout="inline" orientation="vertical" main-align="center">
						<label>----File Metadata----</label>
					</box>
					<field-panel role="content">
						<text-field field-name="Max Archive Depth:" value="config.zipLevel" disable-with="app.configurable" columns="8"
							tooltip="Maximum number of archives to descend into recursively" />
						<radio-buttons field-name="Directory:" render-value-name="type" value="config.fileRequirements.observe(Directory)"
							values="org.quark.searcher.FileAttributeRequirement.values()" disable-with="app.configurable"
							tooltip="Whether matching files may/must/cannot be directories" />
						<radio-buttons field-name="Readable:" render-value-name="type" value="config.fileRequirements.observe(Readable)"
							values="org.quark.searcher.FileAttributeRequirement.values()" disable-with="app.configurable"
							tooltip="Whether matching files may/must/cannot be readable" />
						<radio-buttons field-name="Writable:" render-value-name="type" value="config.fileRequirements.observe(Writable)"
							values="org.quark.searcher.FileAttributeRequirement.values()" disable-with="app.configurable"
							tooltip="Whether matching files may/must/cannot be writable" />
						<radio-buttons field-name="Hidden:" render-value-name="type" value="config.fileRequirements.observe(Hidden)"
							values="org.quark.searcher.FileAttributeRequirement.values()" disable-with="app.configurable"
							tooltip="Whether matching files may/must/cannot be hidden" />
						<box field-name="Size:" layout="inline" orientation="horizontal" main-align="justify" fill="true"
							tooltip="Size range for matching files">
							<text-field value="config.minSize" format="config.byteFormat" disable-with="app.configurable" />
							<label>...</label>
							<text-field value="config.maxSize" format="config.byteFormat" disable-with="app.configurable" />
						</box>
						<box field-name="Last Modified:" layout="inline" orientation="horizontal" main-align="justify" fill="true"
							tooltip="Last modified date range for matching files">
							<text-field value="config.minLM" format="config.timeFormat" disable-with="app.configurable" columns="100" />
							<label>...</label>
							<text-field value="config.maxLM" format="config.timeFormat" disable-with="app.configurable" columns="100" />
						</box>
					</field-panel>
				</collapse-pane>
				<spacer length="3" />
				<table rows="config.excludedFileNames" fill="true" value-name="row" render-value-name="col">
					<titled-border title="Exclude Files" />
					<column name="Pattern" value="row.getPattern()">
						<column-edit type="modify-row-value" edit-value-name="pattern" commit="row.setPattern(pattern)">
							<text-field format="config.patternFormat" />
						</column-edit>
					</column>
					<column name="Case" value="row.isCaseSensitive()">
						<check-box role="renderer" />
						<column-edit type="modify-row-value" edit-value-name="cs" commit="row.setCaseSensitive(cs)">
							<check-box />
						</column-edit>
					</column>
					<multi-value-action value-list-name="blah" icon="icons/add.png" action="config.excludedFileNames.create().create()"
						allow-for-empty="true" />
					<multi-value-action value-list-name="rows" icon="icons/remove.png"
						action="config.excludedFileNames.getValues().removeAll(rows)" allow-for-empty="true" />
				</table>
				<spacer length="3" />
				<box layout="inline" orientation="horizontal" main-align="center" fill="true">
					<button action="ext.searchAction" text="app.searchText" />
				</box>
			</field-panel>
			<split orientation="vertical" split-position="${config.rightSplitDiv + &quot;%&quot;}">
				<tree root="ext.resultRoot" value-name="path" render-value-name="node" children="node.getChildren()"
					selection="app.selectedResult" leaf="!node.getFile().isDirectory()">
					<column name="Tree">
						<!-- These icons are from https://icons8.com, specifically icon/11651/file and icon/21079/folder" -->
						<label role="renderer" value="node.getFile().getName()"
							icon="&quot;icons/icons8-&quot;+(node.getFile().isDirectory() ? &quot;folder-16.png&quot; : &quot;file-50-filled.png&quot;)" />
					</column>
				</tree>
				<box layout="inline" orientation="vertical" main-align="justify" cross-align="justify">
					<!-- visible should really be ""app.selectedResult!=null &amp;&amp; !app.textMatches.isEmpty()" -->
					<label value="&quot;Text Matches In &quot;+app.selectedResult.getFile().getPath()"
						visible="app.selectedResult!=null"/>
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
</quick>
