/*
 * Decompiled with CFR 0.152.
 */
package ttr;

import java.util.Calendar;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import ttr.DBPlayers;
import ttr.DBTempls;
import ttr.DBTours;
import ttr.TTRClient;
import ttr.Utility;
import ttr.events.XmlLoadEvents;

public class DBMain {
    public static final int ID_PLAYER = 0;
    public static final int ID_TOURNAMENT = 1;
    public static final int ID_TEMPLATE = 2;
    private String DBname = "TTW rating";
    private String DBpath = "";
    private String DBfile = "";
    public DBTours tours = new DBTours(this);
    public DBPlayers players = new DBPlayers(this);
    public DBTempls templates = new DBTempls(this);
    public Calendar date = null;
    public boolean bUpdating = false;
    public boolean bChanged = false;
    public int errNumPlsMissing;

    public String toString() {
        return this.DBname + (this.bChanged ? " (*)" : "");
    }

    public String GetDirPath() {
        return this.DBpath;
    }

    public String GetFileName() {
        return this.DBfile;
    }

    public void rebase(java.nio.file.Path header)throws Exception{
        header=header.toAbsolutePath().normalize();players.switchPath(header.getParent().resolve("Players.sqlite"));
        DBpath=header.getParent().toString()+java.io.File.separator;DBfile=header.getFileName().toString();
        players.file="Players.sqlite";players.fileRegions="Regions.xml";templates.file="Templs.xml";
    }

    public boolean IsEmpty() {
        return this.players.players.isEmpty();
    }

    public int GetFreeID(int typeID) {
        int id = 0;
        boolean bOk = true;
        do {
            bOk = true;
            id = 0x1000000 + TTRClient.random.nextInt(0x7000000);
            switch (typeID) {
                case 0: {
                    if (this.players.GetPlayer(id) == null) break;
                    bOk = false;
                    break;
                }
                case 1: {
                    if (!this.tours.IsTournamentExist(id)) break;
                    bOk = false;
                    break;
                }
            }
        } while (!bOk);
        return id;
    }

    public boolean LoadXML(String name, XmlLoadEvents cback) {
        try {
            int n;
            Document doc;
            this.errNumPlsMissing = 0;
            if (cback != null) {
                cback.OnSectionStarted("\u0427\u0438\u0442\u0430\u0435\u0442\u0441\u044f \u0437\u0430\u0433\u043e\u043b\u043e\u0432\u043e\u043a \u0431\u0430\u0437\u044b \u0434\u0430\u043d\u043d\u044b\u0445");
            }
            if ((doc = PlayersUpdateControl.document(name, cback)) == null) {
                if (cback != null) {
                    cback.SetErrorCode(1, "");
                }
                return false;
            }
            Element elmDB = doc.getDocumentElement();
            this.DBname = elmDB.getAttributes().getNamedItem("name").getNodeValue();
            this.date = DatabaseDate.fromHeader(elmDB);
            this.DBpath = (n = Math.max(name.lastIndexOf("/"), name.lastIndexOf("\\"))) >= 0 ? name.substring(0, n + 1) : "";
            this.DBfile = n >= 0 ? name.substring(n + 1) : name;
            Element elm = Utility.getXMLElement("PlayerDB", elmDB);
            String fname = elm.getAttributes().getNamedItem("name").getNodeValue();
            if(PlayersUpdateControl.current()!=null&&!PlayerProfileSource.safeRemoteName(fname))throw new IllegalArgumentException("Недопустимое имя файла: "+fname);
            if (!this.players.LoadXML(this.DBpath + fname, cback)) {
                return false;
            }
            elm = Utility.getXMLElement("RegionList", elmDB);
            if (elm != null) {
                fname = elm.getAttributes().getNamedItem("name").getNodeValue();
                if(PlayersUpdateControl.current()!=null&&!PlayerProfileSource.safeRemoteName(fname))throw new IllegalArgumentException("Недопустимый файл регионов: "+fname);
                boolean regionsLoaded=this.players.LoadRegionList(this.DBpath + fname);
                if(PlayersUpdateControl.current()!=null&&!regionsLoaded)throw new IllegalStateException("Не удалось прочитать регионы: "+fname);
            }
            elm = Utility.getXMLElement("TemplDB", elmDB);
            fname = elm.getAttributes().getNamedItem("name").getNodeValue();
            if(PlayersUpdateControl.current()!=null&&!PlayerProfileSource.safeRemoteName(fname))throw new IllegalArgumentException("Недопустимый файл шаблонов: "+fname);
            boolean templatesLoaded=this.templates.LoadXML(this.DBpath + fname, PlayersUpdateControl.current()==null?cback:null);
            PlayersUpdateControl.checkpoint();
            if(PlayersUpdateControl.current()!=null&&!templatesLoaded)return false;
            this.bChanged = false;
        }
        catch (Exception e) {
            PlayersUpdateControl c=PlayersUpdateControl.current();if(c!=null)c.failed(e);
            return false;
        }
        return true;
    }

    public boolean SaveXML(String name, boolean bSetPath) {
        try {
            boolean bCopy;
            boolean bl = bCopy = !name.isEmpty() && !name.equalsIgnoreCase(this.DBpath + this.DBfile);
            if (name.isEmpty() && !this.DBpath.isEmpty() && !this.DBfile.isEmpty()) {
                name = this.DBpath + this.DBfile;
            }
            if (name.isEmpty()) {
                return false;
            }
            int n = Math.max(name.lastIndexOf("/"), name.lastIndexOf("\\"));
            String path = n >= 0 ? name.substring(0, n + 1) : "";
            String file = n >= 0 ? name.substring(n + 1) : name;
            Document doc = Utility.CreateXMLDocument();
            Element elmDB = doc.createElement("DB");
            elmDB.setAttribute("name", this.DBname);
            if (this.date != null) {
                elmDB.setAttribute("date", String.format("%1$td.%1$tm.%1$tY-%1$tH:%1$tM:%1$tS", this.date));
            }
            doc.appendChild(elmDB);
            Element elm = doc.createElement("PlayerDB");
            elm.setAttribute("name", this.players.file);
            elmDB.appendChild(elm);
            elm = doc.createElement("RegionList");
            elm.setAttribute("name", this.players.fileRegions);
            elmDB.appendChild(elm);
            elm = doc.createElement("TemplDB");
            elm.setAttribute("name", this.templates.file);
            elmDB.appendChild(elm);
            if (!this.players.SaveXML(path + this.players.file)) return false;
            if (!this.players.SaveRegionList(path + this.players.fileRegions)) return false;
            if (!this.templates.SaveXML(path + this.templates.file)) return false;
            if (!Utility.SaveXMLDocument(name, doc)) return false;
            if (bSetPath) {
                this.players.switchPath(java.nio.file.Paths.get(path + this.players.file));
                this.DBpath = path;
                this.DBfile = file;
            }
            this.bChanged = false;
        }
        catch (Exception e) {
            return false;
        }
        return true;
    }
}
