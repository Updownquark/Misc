<?xml version="1.0" encoding="UTF-8"?>

<qonfig-app uses:app="Qonfig-App v0.1" app-file="qommons-searcher.qml">
	<toolkit def="/org/observe/expresso/expresso-core.qtd">
		<value-type>org.observe.expresso.ExpressionValueType</value-type>
	</toolkit>
	<toolkit def="/org/observe/expresso/expresso-base.qtd" />
	<toolkit def="/org/observe/expresso/expresso-config.qtd" />
	<toolkit def="/org/observe/quick/style/quick-style.qtd" />
	<toolkit def="/org/observe/quick/quick-core.qtd" />
	<toolkit def="/org/observe/quick/quick-base.qtd" />
	<toolkit def="/org/observe/quick/quick-ext.qtd" />
	<toolkit def="/org/observe/quick/quick-swing.qtd" />
	<special-session>org.observe.expresso.ExpressoSessionImplV0_1</special-session>
	<special-session>org.observe.quick.style.StyleSessionImplV0_1</special-session>
	<interpretation>org.observe.expresso.ExpressoBaseV0_1</interpretation>
	<interpretation>org.observe.expresso.ExpressoConfigV0_1</interpretation>
	<interpretation>org.observe.quick.style.QuickStyle</interpretation>
	<interpretation>org.observe.quick.QuickCore</interpretation>
	<interpretation>org.observe.quick.QuickBase</interpretation>
	<interpretation>org.observe.quick.QuickX</interpretation>
	<interpretation>org.observe.quick.QuickSwing</interpretation>
</qonfig-app>
