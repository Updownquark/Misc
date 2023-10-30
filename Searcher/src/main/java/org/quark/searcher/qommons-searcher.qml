<?xml version="1.0" encoding="UTF-8"?>

<quick xmlns:base="Quick-Base v0.1" xmlns:swing="Quick-Swing v0.1" xmlns:x="Quick-X v0.1" xmlns:expresso="Expresso-Config v0.1"
	xmlns:exDebug="Expresso-Debug v0.1"
	with-extension="swing:quick-swing,window"
	look-and-feel="system" title="`Qommons Searcher`"
	x="config.x" y="config.y" width="config.width" height="config.height" close-action="exit">
	<head>
		<imports>
			<import>org.quark.searcher.*</import>
			<import>org.qommons.io.BetterFile</import>
		</imports>
		<models>
			<model name="formats">
				<archive-enabled-file-source name="files" max-archive-depth="config.zipLevel">
					<zip-archival />
					<tar-archival />
					<gz-archival />
				</archive-enabled-file-source>
				<constant name="workingDir">System.getProperty("user.dir")</constant>
				<file-format name="fileFormat" working-dir="BetterFile.at(files, workingDir)" file-source="files" />
				<regex-format-string name="patternFormat" />
				<double-format name="byteFormat" sig-digs="4" unit="b" metric-prefixes-p2="true" />
				<instant-format name="timeFormat" max-resolution="Minute" relative-evaluation="Past" />
				<standard-text-format name="fileReqFormat" type="FileAttributeRequirement" />

				<!-- config formats -->
				<text-config-format name="fileConfigFormat" text-format="fileFormat" default="files.at(`.`)"/>
				<text-config-format name="patternConfigFormat" text-format="patternFormat" />
				<text-config-format name="fileReqConfigFormat" text-format="fileReqFormat" default="Maybe" />
			</model>
			<config name="config" config-name="qommons-search">
				<value name="zipLevel" type="int" default="10" config-path="zip-level" />
				<value name="x" type="int" />
				<value name="y" type="int" />
				<value name="width" type="int" />
				<value name="height" type="int" />
				<value name="searchBase" type="BetterFile" format="formats.fileConfigFormat" />
				<value name="fileNamePattern" type="String" format="formats.patternConfigFormat" />
				<value name="fileNameRegex" type="boolean" default="true" />
				<value name="fileNameCaseSensitive" type="boolean" default="false" />
				<value name="fileTextPattern" type="String" format="formats.patternConfigFormat" />
				<value name="fileTextRegex" type="boolean" default="true" />
				<value name="fileTextCaseSensitive" type="boolean" default="false" />
				<value name="multiContentMatches" type="boolean" default="true" />
				<value name="maxFileMatchLength" type="int" default="10000" />
				<value-set name="excludedFileNames" type="PatternConfig" />
				<value  name="directoryRequirement" type="FileAttributeRequirement" format="formats.fileReqConfigFormat" />
				<map name="fileRequirements" key-type="BetterFile.FileBooleanAttribute" type="FileAttributeRequirement"
					format="formats.fileReqConfigFormat" />
				<value name="minSize" type="double" default="0" />
				<value name="maxSize" type="double" default="QuickSearcher.DEFAULT_MAX_SIZE" />
				<value name="minLM" type="java.time.Instant" default="`Jan 01 1900 12:00am`" />
				<value name="maxLM" type="java.time.Instant" default="`Jan 01 3000 12:00am`" />
				<!-- TODO Filter accept the mins/maxes above to keep max>min -->

				<value name="mainSplitDiv" type="double" default="25" />
				<value name="rightSplitDiv" type="double" default="40" />
				<value name="textMatchSplitDiv" type="double" default="40" />
			</config>
			<model name="app">
				<constant name="searcher">new QuickSearcher(config.searchBase, config.fileNamePattern,
					config.excludedFileNames, config.minSize, config.maxSize, config.minLM, config.maxLM)
				</constant>
				<!-- These transformations need the type specified so they are interpreted as values of the given type,
					since QuickSearcher provides these as observables so the app can be notified of changes.
					Without the type specified, these variables would be of type ObservableValue<Whatever> instead of type Whatever.
				-->
				<transform name="searchBase" source="config.searchBase">
					<map-to source-as="betterFile">
						<map-with>betterFile==null ? null : org.qommons.io.FileUtils.asFile(betterFile)</map-with>
						<map-reverse target-as="javaFile" type="replace-source" inexact="true">
							javaFile==null ? null : formats.files.at(javaFile.getAbsolutePath())
						</map-reverse>
					</map-to>
				</transform>
				<value name="status" type="QuickSearcher.SearchStatus">searcher.getStatus()</value>
				<value name="statusMessage" type="String">searcher.getStatusMessage()</value>
				<value name="resultRoot" type="QuickSearcher.SearchResultNode">searcher.getResultRoot()</value>
				<value name="selectedResult" type="QuickSearcher.SearchResultNode">searcher.getSelectedResult()</value>
				<value name="configurable" type="String">searcher.isConfigurable()</value>
				<value name="searchText" type="String">searcher.getSearchText()</value>
				<value name="searchEnabled" type="String">searcher.isSearchEnabled()</value>
				<value name="selectedTextMatch" type="QuickSearcher.TextResult" />
				<hook name="renderTextHook" on="selectedTextMatch">searcher.renderTextResult(selectedTextMatch)</hook>
				<!--<transform name="status" source="searcher" break-on="createValue">
					<map-to type="QuickSearcher.SearchStatus" source-as="srch">
						<map-with>srch.getStatus()</map-with>
					</map-to>
				</transform>
				<transform name="statusMessage" source="searcher">
					<map-to type="String" source-as="srch">
						<map-with>srch.getStatusMessage()</map-with>
					</map-to>
				</transform>
				<transform name="resultRoot" source="searcher">
					<map-to type="QuickSearcher.SearchResultNode" source-as="srch">
						<map-with>srch.getResultRoot()</map-with>
					</map-to>
				</transform>
				<transform name="selectedResult" source="searcher">
					<map-to type="QuickSearcher.SearchResultNode" source-as="srch">
						<map-with>srch.getSelectedResult()</map-with>
					</map-to>
				</transform>
				<transform name="configurable" source="searcher">
					<map-to type="String" source-as="srch">
						<map-with>srch.isConfigurable()</map-with>
					</map-to>
				</transform>
				<transform name="searchText" source="searcher">
					<map-to type="String" source-as="srch">
						<map-with>srch.getSearchText()</map-with>
					</map-to>
				</transform>
				<transform name="searchEnabled" source="searcher">
					<map-to type="String" source-as="srch">
						<map-with>srch.isSearchEnabled()</map-with>
					</map-to>
				</transform>-->
				<action name="_searchAction">searcher.search(config.searchBase,
					config.fileNamePattern, config.fileNameRegex, config.fileNameCaseSensitive,
					config.fileTextPattern, config.fileTextRegex, config.fileTextCaseSensitive,
					config.multiContentMatches, config.maxFileMatchLength, config.fileRequirements)
				</action>
				<value name="searchUIEnabled">searcher.isSearchUiEnabled()</value>
				<value name="searchActionEnabled">searcher.isSearchActionEnabled()</value>
				<transform name="searchAction" source="_searchAction">
					<disable with="searchActionEnabled" />
				</transform>
				<value name="textMatches">selectedResult==null ? null : selectedResult.getTextResults()</value>
				<!--<transform name="textMatches" source="selectedResult">
					<map-to source-as="res" null-to-null="true">
						<map-with>res.getTextResults()</map-with>
					</map-to>
					<flatten />
				</transform>
				<value name="selectedTextMatch" type="QuickSearcher.TextResult" />
				<transform name="selectedText" source="selectedTextMatch">
					<map-to source-as="tm">
						<map-with>QuickSearcher.renderTextResult(tm)</map-with>
					</map-to>
				</transform>-->
				<hook name="initFileRequirements">QuickSearcher.initializeFileRequirements(config.fileRequirements)</hook>
				<value name="isFileNameFiltered">config.fileNamePattern==null || config.fileNamePattern.isEmpty()</value>
				<value name="isTextFiltered">config.fileTextPattern!=null &amp; !config.fileTextPattern.isEmpty()</value>
			</model>
		</models>
		<style-sheet>
			<import-style-sheet name="searcher" ref="qommons-searcher.qss" />
			<style element="table">
				<style child="border">
					<style attr="thickness" if="config.fileNameCaseSensitive">2</style>
				</style>
			</style>
		</style-sheet>
	</head>
	<box layout="inline-layout" orientation="vertical" main-align="justify" cross-align="justify">
		<split orientation="horizontal" split-position="config.mainSplitDiv *`1%`">
			<field-panel>
				<box layout="inline-layout" orientation="horizontal" main-align="justify" field-label="`Search In:`" fill="true">
					<text-field value="config.searchBase" format="formats.fileFormat" disable-with="app.searchUIEnabled" columns="50"
						tooltip="`Root folder or file to search in`" />
					<file-button open="true" value="app.searchBase" disable-with="app.configurable"
						tooltip="`Root folder or file to search in`" />
				</box>
				<spacer length="3" />
				<box layout="inline-layout" orientation="horizontal" main-align="center" fill="true">
					<label>----File Name----</label>
				</box>
				<box field-label="`File Pattern:`" layout="inline-layout" orientation="horizontal" main-align="justify" fill="true">
					<text-field value="config.fileNamePattern" format="formats.patternFormat" disable-with="app.searchUIEnabled"
						commit-on-type="true" tooltip="`Pattern of file names to search for`" />
					<check-box value="config.fileNameRegex"
						disable-with="app.configurable || (app.isFileNameFiltered ? &quot;No File Pattern&quot; : null)"
						tooltip="`Whether the file pattern is evaluated as a regular expression`">`Regex:`</check-box>
					<check-box value="config.fileNameCaseSensitive"
						disable-with="app.configurable || (app.isFileNameFiltered ? &quot;No File Pattern&quot; : null)"
						tooltip="`Whether the file pattern is evaluated case-sensitively`">`Case:`</check-box>
				</box>
				<box layout="inline-layout" orientation="horizontal" main-align="justify" field-label="`Test File:`" fill="true"
					visible="!app.isFileNameFiltered &amp; config.fileNameRegex">
					<model>
						<value name="_testFilePath" type="BetterFile" />
						<transform name="testFilePath" source="_testFilePath">
							<refresh on="config.fileNamePattern" />
							<refresh on="config.fileNameRegex" />
							<refresh on="config.fileNameCaseSensitive" />
						</transform>
						<transform name="testFile" source="testFilePath">
							<map-to source-as="betterFile">
								<map-with>betterFile==null ? null : org.qommons.io.FileUtils.asFile(betterFile)</map-with>
								<map-reverse target-as="javaFile" type="replace-source" inexact="true">
									javaFile==null ? null : formats.files.at(javaFile.getAbsolutePath())
								</map-reverse>
							</map-to>
						</transform>
						<file-format name="fileNamePatternFormat" working-dir="formats.files.at(formats.workingDir)" allow-empty="true" file-source="formats.files">
							<filter-validation test="app.searcher.filePatternMatches(filterValue, config.fileNamePattern,
							config.fileNameRegex, config.fileNameCaseSensitive)" />
						</file-format>
					</model>
					<text-field value="testFilePath" format="fileNamePatternFormat" disable-with="app.searchUIEnabled"
						commit-on-type="true" tooltip="`Enter a file name to test the file pattern against it`" />
					<file-button open="true" value="testFile" disable-with="app.searchUIEnabled"
						tooltip="`Enter a file name to test the file pattern against it`" />
				</box>
				<spacer length="3" />
				<box layout="inline-layout" orientation="horizontal" main-align="center" fill="true">
					<label>
						<style attr="font-color" if="rightPressed">`red`</style>
						----File Content----
					</label>
				</box>
				<box field-label="`Text Pattern:`" layout="inline-layout" orientation="horizontal" main-align="justify" fill="true">
					<model>
						<value name="textPatternEmptyMsg">config.fileTextPattern==null || config.fileTextPattern.isEmpty() ? "No Text Pattern set" : null</value>
						<value name="textPatternModEnabled">app.configurable || textPatternEmptyMsg</value>
					</model>
					<text-field value="config.fileTextPattern" format="formats.patternFormat" disable-with="app.searchUIEnabled"
						commit-on-type="true" tooltip="`Text to search for in matching files`">
						<style attr="color" if="hovered">`green`</style>
					</text-field>
					<check-box value="config.fileTextRegex" disable-with="app.searchUIEnabled || textPatternModEnabled"
						tooltip="`Whether the file content pattern is evaluated as a regular expression`">`Regex:`</check-box>
					<check-box value="config.fileTextCaseSensitive" disable-with="app.searchUIEnabled || textPatternModEnabled" 
						tooltip="`Whether the text pattern is evaluated case-sensitively`">`Case:`</check-box>
				</box>
				<box field-label="`Test Text:`" layout="border-layout" fill="true" visible="app.isTextFiltered">
					<model>
						<value name="_testFileContent" type="String" />
						<transform name="testFileContent" source="_testFileContent">
							<refresh on="config.fileTextPattern" />
							<refresh on="config.fileTextRegex" />
							<refresh on="config.fileTextCaseSensitive" />
						</transform>
						<standard-text-format name="fileContentPatternFormat" type="String">
							<filter-validation test="app.searcher.contentPatternMatches(filterValue, config.fileTextPattern,
							config.fileTextRegex, config.fileTextCaseSensitive) ? null : &quot;Content does not match pattern&quot;" />
						</standard-text-format>
					</model>
					<text-field region="center" value="testFileContent" format="fileContentPatternFormat" disable-with="app.searchUIEnabled"
						commit-on-type="true" tooltip="`Enter text to test the text pattern against it`" />
				</box>
				<check-box value="config.multiContentMatches" field-label="`Multiple Text Matches:`"
					disable-with="app.searchUIEnabled || (app.isTextFiltered ? null : &quot;No text matcher specified&quot;)" />
				<spacer length="3" />
				<collapse-pane fill="true" name="debug">
					<box role="header" layout="inline-layout" orientation="vertical" main-align="center">
						<label>----File Metadata----</label>
					</box>
					<field-panel role="content">
						<text-field field-label="`Max Archive Depth:`" value="config.zipLevel" disable-with="app.searchUIEnabled" columns="8"
							tooltip="`Maximum number of archives to descend into recursively`" />
						<radio-buttons field-label="`Directory:`" value="config.directoryRequirement"
							values="FileAttributeRequirement.values()" disable-with="app.searchUIEnabled"
							tooltip="`Whether matching files may/must/cannot be directories`" />
						<radio-buttons field-label="`Readable:`" value="config.fileRequirements.observe(Readable)"
							values="FileAttributeRequirement.values()" disable-with="app.searchUIEnabled"
							tooltip="`Whether matching files may/must/cannot be readable`" />
						<radio-buttons field-label="`Writable:`" value="config.fileRequirements.observe(Writable)"
							values="FileAttributeRequirement.values()" disable-with="app.searchUIEnabled"
							tooltip="`Whether matching files may/must/cannot be writable`" />
						<radio-buttons field-label="`Hidden:`" value="config.fileRequirements.observe(Hidden)"
							values="FileAttributeRequirement.values()" disable-with="app.searchUIEnabled"
							tooltip="`Whether matching files may/must/cannot be hidden`" />
						<box field-label="`Size:`" layout="inline-layout" orientation="horizontal" main-align="justify" fill="true"
							tooltip="`Size range for matching files`">
							<text-field value="config.minSize" format="formats.byteFormat" disable-with="app.searchUIEnabled" columns="20" />
							<label>...</label>
							<text-field value="config.maxSize" format="formats.byteFormat" disable-with="app.searchUIEnabled" columns="20" />
						</box>
						<box field-label="`Last Modified:`" layout="inline-layout" orientation="horizontal" main-align="justify" fill="true"
							tooltip="`Last modified date range for matching files`">
							<text-field value="config.minLM" format="formats.timeFormat" disable-with="app.searchUIEnabled" columns="20" />
							<label>...</label>
							<text-field value="config.maxLM" format="formats.timeFormat" disable-with="app.searchUIEnabled" columns="20" />
						</box>
					</field-panel>
				</collapse-pane>
				<spacer length="3" />
				<table rows="config.excludedFileNames" fill="true" active-value-name="fileName">
					<titled-border title="`Exclude Files`">
						<style attr="border-color" if="config.multiContentMatches">`blue`</style>
						<style attr="font-weight" if="config.fileNameCaseSensitive">`bold`</style>
						<style attr="font-slant" if="config.fileTextCaseSensitive">`italic`</style>
						<style attr="border-color" if="hovered">`green`</style>
					</titled-border>
					<column name="`Pattern`" value="fileName.getPattern()">
						<column-edit type="modify-row-value" column-edit-value-name="pattern" commit="fileName.setPattern(pattern)">
							<text-field format="formats.patternFormat" />
						</column-edit>
					</column>
					<column name="`Case`" value="fileName.isCaseSensitive()">
						<check-box value="columnValue" />
						<column-edit type="modify-row-value" column-edit-value-name="caseS" commit="fileName.setCaseSensitive(caseS)">
							<check-box />
						</column-edit>
					</column>
					<multi-value-action icon="&quot;icons/add.png&quot;" allow-for-empty="true">
						config.excludedFileNames.create().create()
					</multi-value-action>
					<multi-value-action icon="&quot;icons/remove.png&quot;" allow-for-empty="false">
						config.excludedFileNames.getValues().removeAll(actionValues)
					</multi-value-action>
				</table>
				<spacer length="3" />
				<box layout="inline-layout" orientation="horizontal" main-align="center" fill="true">
					<button action="app.searchAction">app.searchText</button>
				</box>
			</field-panel>
			<split orientation="vertical" split-position="config.rightSplitDiv * `1%`">
				<tree active-node-name="result" node-selection="app.selectedResult">
					<dynamic-tree-model value="app.resultRoot" children="result==null ? null : result.getChildren()" leaf="result==null || !result.getFile().isDirectory()" />
					<column name="`Tree`">
						<!-- These icons are from https://icons8.com,
							  specifically icon/11651/file and icon/21079/folder" -->
						<label value="result.getFile().getName()"
							icon="&quot;icons/icons8-&quot;+(result.getFile().isDirectory() ? &quot;folder-16.png&quot; : &quot;file-50-filled.png&quot;)" />
					</column>
				</tree>
				<split orientation="vertical" split-position="config.textMatchSplitDiv * `1%`">
					<box layout="inline-layout" orientation="vertical" main-align="justify" cross-align="justify" visible="app.isTextFiltered">
						<label value="app.selectedResult==null ? `` : (`Text Matches In `+app.selectedResult.getFile().getPath())"
							visible="app.selectedResult!=null"/>
						<table rows="app.textMatches" selection="app.selectedTextMatch" active-value-name="match">
							<column name="`Value`" value="match.getValue()" />
							<column name="`Pos`" value="match.getPosition()" />
							<column name="`Line`" value="match.getLineNumber()" />
							<column name="`Col`" value="match.getColumnNumber()" />
						</table>
					</box>
					<text-area rows="10" editable="false">
						<dynamic-styled-document root="app.searcher.currentTextResult" children="node.getChildren()">
							<text-style>
								<style style-set="searcher.base.simpleTreeModelData" />
							</text-style>
						</dynamic-styled-document>
					</text-area>
				</split>
			</split>
		</split>
		<label value="app.statusMessage" />
	</box>
</quick>

