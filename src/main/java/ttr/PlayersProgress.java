package ttr;

import ttr.events.XmlLoadEvents;
import ttr.updates.DownloadStats;

/** Worker callbacks only change measurements; Swing polls at 1000 ms. */
public class PlayersProgress implements XmlLoadEvents {
    public static final String CONNECT="Соединение с сервером…", READ="Загрузка и импорт XML", INDEX="Создание индексов", CHECK="Проверка базы", APPLY="Применение обновления";
    private final long started=System.nanoTime();
    private volatile String phase=CONNECT;
    private volatile long records;
    private volatile DownloadStats stats;
    private boolean reading;
    private volatile long finished;
    public synchronized void OnSectionStarted(String section){
        if(reading&&!READ.equals(section)){stats.downloaded();reading=false;}
        phase=section;
    }
    public synchronized void OnBytesRead(long current,long total){
        if(!READ.equals(phase))return;
        if(stats==null){stats=new DownloadStats(total>0?total:-1);reading=true;phase=READ;}
        stats.received(current);
    }
    public void OnElementParsed(long current,long total){if(reading())records=current;}
    public void SetErrorCode(int code,String message){}
    public String phase(){return phase;}
    public long records(){return records;}
    public long elapsed(){return Math.max(0,((finished==0?System.nanoTime():finished)-started)/1000000000L);}
    public DownloadStats.Snapshot snapshot(){DownloadStats s=stats;return s==null?null:s.snapshot();}
    public synchronized boolean reading(){return reading;}
    public void finish(){finished=System.nanoTime();}
}
