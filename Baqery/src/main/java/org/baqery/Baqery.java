package org.baqery;

import java.io.*;

import javax.swing.JPanel;

import org.observe.config.ObservableConfig;
import org.xml.sax.SAXException;

public class Baqery extends JPanel {
	private final ObservableConfig theConfig;

	public Baqery() {
		theConfig = ObservableConfig.createRoot("baqery");
		String filePath = System.getProperty("baqery.config");
		if (filePath == null)
			filePath = "baqery.xml";
		File configFile = new File(filePath);
		ObservableConfig.XmlEncoding encoding = ObservableConfig.XmlEncoding.DEFAULT;
		if (configFile.exists()) {
			try (FileInputStream in = new FileInputStream(configFile)) {
				ObservableConfig.readXml(theConfig, in, encoding);
			} catch (IOException e) {
				System.err.println("Could not read " + configFile.getAbsolutePath());
				e.printStackTrace();
			} catch (SAXException e) {
				System.err.println("Could not parse " + configFile.getAbsolutePath());
				e.printStackTrace();
			}
		}
		theConfig.persistOnShutdown(config -> {
			try (Writer out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(configFile)))) {
				ObservableConfig.writeXml(config, out, encoding, "\t");
			}
		}, ex -> {
			System.err.println("Could not save " + configFile.getAbsolutePath());
			ex.printStackTrace();
		});
	}
}
