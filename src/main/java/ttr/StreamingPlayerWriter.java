package ttr;

import java.io.*;
import java.nio.file.*;
import java.util.Map;
import javax.xml.stream.*;
import org.w3c.dom.*;

/** Streams the existing Player.SaveXML representation, retaining one Player DOM. */
public final class StreamingPlayerWriter {
    private StreamingPlayerWriter() {}

    public static boolean save(DBPlayers db, String filename) {
        Path staging = null;
        try {
            Path destination = Paths.get(filename).toAbsolutePath();
            staging = Files.createTempFile(destination.getParent(), ".ttwr-save-", ".xml.part");
            Document document = Utility.CreateXMLDocument();
            if (document == null) throw new IOException("Cannot create player document");
            Element parent = document.createElement("Players");
            document.appendChild(parent);
            try (OutputStream output = new BufferedOutputStream(Files.newOutputStream(staging), 65536)) {
                XMLStreamWriter writer = XMLOutputFactory.newInstance().createXMLStreamWriter(output, "UTF-8");
                try {
                    writer.writeStartDocument("UTF-8", "1.0");
                    writer.writeCharacters("\n");
                    writer.writeStartElement("Players");
                    for (Map.Entry<Integer, Player> entry : db.players.entrySet()) {
                        Element player = entry.getValue().SaveXML(document, parent, false);
                        if (player == null) throw new IOException("Cannot serialize player " + entry.getKey());
                        try {
                            writer.writeCharacters("\n    ");
                            writeElement(writer, player, 1);
                        } finally { parent.removeChild(player); }
                    }
                    writer.writeCharacters("\n");
                    writer.writeEndElement();
                    writer.writeCharacters("\n");
                    writer.writeEndDocument();
                    writer.flush();
                } finally { writer.close(); }
            }
            try { Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException error) {
                Files.move(staging, destination, StandardCopyOption.REPLACE_EXISTING);
            }
            staging = null;
            int separator = filename.lastIndexOf('\\');
            db.file = separator < 0 ? filename : filename.substring(separator + 1);
            return true;
        } catch (Exception error) {
            return false;
        } finally {
            if (staging != null) {
                try { Files.deleteIfExists(staging); } catch (IOException ignored) { }
            }
        }
    }

    private static void writeElement(XMLStreamWriter writer, Element element, int depth) throws XMLStreamException {
        writer.writeStartElement(element.getTagName());
        NamedNodeMap attributes = element.getAttributes();
        for (int i=0; i<attributes.getLength(); i++) {
            Node attribute = attributes.item(i);
            writer.writeAttribute(attribute.getNodeName(), attribute.getNodeValue());
        }
        boolean elements = false;
        for (Node child=element.getFirstChild(); child!=null; child=child.getNextSibling()) {
            if (child.getNodeType()==Node.ELEMENT_NODE) {
                elements = true;
                indent(writer, depth + 1);
                writeElement(writer, (Element)child, depth + 1);
            } else if (child.getNodeType()==Node.TEXT_NODE || child.getNodeType()==Node.CDATA_SECTION_NODE) {
                writeText(writer, child.getNodeValue());
            } else throw new XMLStreamException("Unexpected node in Player.SaveXML: " + child.getNodeType());
        }
        if (elements) indent(writer, depth);
        writer.writeEndElement();
    }

    private static void indent(XMLStreamWriter writer, int depth) throws XMLStreamException {
        writer.writeCharacters("\n");
        for (int i=0; i<depth; i++) writer.writeCharacters("    ");
    }

    private static void writeText(XMLStreamWriter writer, String text) throws XMLStreamException {
        if (text == null || text.isEmpty()) return;
        // XML parsers normalize literal CR to LF. Preserve CR as a character reference,
        // matching the original DOM transformer rather than changing field contents.
        int start = 0;
        for (int i=0; i<text.length(); i++) {
            char value = text.charAt(i);
            if (value=='\r') {
                writer.writeCharacters(text.substring(start, i));
                writer.writeEntityRef("#13");
                start=i+1;
            } else if (Character.isHighSurrogate(value)) {
                if (i+1>=text.length() || !Character.isLowSurrogate(text.charAt(i+1)))
                    throw new XMLStreamException("Unpaired surrogate in player field");
                i++;
            } else if (Character.isLowSurrogate(value) || (value<0x20 && value!='\n' && value!='\t')
                    || value==0xFFFE || value==0xFFFF) {
                throw new XMLStreamException("Invalid XML 1.0 character in player field");
            }
        }
        writer.writeCharacters(text.substring(start));
    }
}
