package ttr.updates;

import ttr.PortablePaths;
import ttr.TTRClient;
import java.awt.event.WindowEvent;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ExecutionException;
import javax.swing.*;

/** Swing integration: network work off the EDT; installation only after confirmed exit. */
public final class ProgramUpdates {
    private static boolean checking;
    private static Path pending;
    private static String pendingVersion;
    private static UpdateStorage.Report storageReport;
    private ProgramUpdates() {}
    public static void checkManually(TTRClient client) { check(client,true); }
    public static void attach(final TTRClient client) {
        JMenuBar bar = client.getJMenuBar();
        if (bar == null) return;
        JMenu help = null;
        for (int i = 0; i < bar.getMenuCount(); i++) {
            JMenu menu = bar.getMenu(i);
            if (menu != null && menu.getText() != null && menu.getText().replace("&", "").contains("Справка")) help = menu;
        }
        if (help == null) { help = new JMenu("Обновление программы"); bar.add(help); }
        JMenuItem item = new JMenuItem("Проверить обновления программы…");
        item.addActionListener(event -> check(client, true)); help.addSeparator(); help.add(item);
        JMenuItem storage=new JMenuItem("Файлы обновлений…");
        storage.addActionListener(event -> showStorage(client));help.add(storage);
        javax.swing.Timer cleanup=new javax.swing.Timer(5000,event -> {
            if(!client.isShowing())return;
            new SwingWorker<UpdateStorage.Report,Void>(){
                protected UpdateStorage.Report doInBackground()throws Exception{return UpdateStorage.afterSuccessfulStart(PortablePaths.home(),AppVersion.current());}
                protected void done(){try{storageReport=get();}catch(Exception error){System.err.println("Update storage retained: "+cause(error));}}
            }.execute();
        });cleanup.setRepeats(false);cleanup.start();
        // Check once per day; manual checks always bypass this cache.
        javax.swing.Timer timer = new javax.swing.Timer(3000, event -> check(client, false)); timer.setRepeats(false); timer.start();
    }
    private static void showStorage(final TTRClient client){
        new SwingWorker<Long,Void>(){
            protected Long doInBackground()throws Exception{return UpdateStorage.bytes(PortablePaths.home());}
            protected void done(){try{
                String text=String.format(Locale.ROOT,"Файлы обновлений занимают %.1f МБ.",get()/1048576.0)
                    +"\nПосле успешного запуска сохраняется последняя проверенная резервная копия."
                    +"\nНеудачные и незавершённые обновления сохраняются для диагностики.";
                if(storageReport!=null&&storageReport.backup!=null)text+="\nРезервная копия: "+storageReport.backup;
                text+="\nПапка: "+PortablePaths.profile().resolve("updates");message(client,text);
            }catch(Exception error){failure(client,error);}}
        }.execute();
    }
    private static void check(final TTRClient client, final boolean manual) {
        if (checking) { if (manual) message(client,"Проверка или загрузка обновления уже выполняется."); return; }
        final Path stamp = PortablePaths.profile().resolve("program-update-check.txt");
        if (!manual) try { if (Files.exists(stamp) && System.currentTimeMillis()-Files.getLastModifiedTime(stamp).toMillis() < 86400000L) return; } catch (Exception ignored) {}
        checking = true;
        new SwingWorker<ReleaseClient.Release,Void>() {
            protected ReleaseClient.Release doInBackground() throws Exception {
                ReleaseClient.Release release = new ReleaseClient().latest();
                Files.write(stamp, Arrays.asList("checked")); return release;
            }
            protected void done() {
                checking = false;
                if (!client.isDisplayable()) return;
                try {
                    ReleaseClient.Release release = get();
                    if (release == null || !AppVersion.newer(release.version, AppVersion.current())) { if (manual) message(client,"Установлена актуальная версия " + AppVersion.current() + "."); return; }
                    int choice = JOptionPane.showConfirmDialog(client,"Доступна версия " + release.version + ". Установлена " + AppVersion.current()
                            + ".\nСкачать обновление? После загрузки программа предложит закрытие и перезапуск."
                            + "\nБаза, настройки и встроенная Java сохранятся.","Обновление TTWR SQLite",JOptionPane.YES_NO_OPTION);
                    if (choice == JOptionPane.YES_OPTION) download(client,release);
                } catch (Exception error) { if (manual) failure(client,error); else System.err.println("Program update check: " + cause(error)); }
            }
        }.execute();
    }
    private static void download(final TTRClient client, final ReleaseClient.Release release) {
        checking = true;
        client.setEnabled(false);
        final DownloadStats stats=new DownloadStats(release.size);
        final DownloadControl control=new DownloadControl();
        final UpdateDownloadDialog progress=new UpdateDownloadDialog(client,release.version,stats,control);progress.setVisible(true);
        new SwingWorker<Path,Void>() {
            protected Path doInBackground() throws Exception {
                Path stage=null;
                try {
                    control.start();Path updates=PortablePaths.profile().resolve("updates");Files.createDirectories(updates);
                    stage=Files.createTempDirectory(updates,"update-");control.check();
                    new ReleaseClient().download(release,stage.resolve("release.zip"),new ReleaseClient.Progress(){
                        public void received(long bytes,long total){stats.received(bytes);progress.phase("Загружаем обновление…");}
                        public void downloaded(){stats.downloaded();progress.phase("Проверяем архив обновления…");}
                    },control);
                    control.check();progress.phase("Проверяем и подготавливаем файлы…");
                    UpdateInstaller.extract(stage.resolve("release.zip"),stage.resolve("image"),release.version);control.check();
                    Path running=Paths.get(ProgramUpdates.class.getProtectionDomain().getCodeSource().getLocation().toURI());
                    Files.copy(running,stage.resolve("updater.dat"));control.check();return stage;
                } catch(Exception error){
                    boolean interrupted=Thread.interrupted();
                    try{if(stage!=null)removeStage(stage);}catch(Exception cleanup){throw new CleanupFailure(stage,cleanup);}
                    finally{if(interrupted)Thread.currentThread().interrupt();}
                    throw error;
                } finally {control.finish();}
            }
            protected void done() {
                final Path stage;
                try{stage=get();}catch(Exception error){
                    finishDownload(client,progress);
                    if(!control.isCancelled()||cause(error) instanceof CleanupFailure)failure(client,error);
                    return;
                }
                // Cancellation can arrive after the worker returned but before this EDT callback.
                if(!control.accept()){
                    new SwingWorker<Void,Void>(){
                        protected Void doInBackground()throws Exception{removeStage(stage);return null;}
                        protected void done(){finishDownload(client,progress);try{get();}catch(Exception e){failure(client,new CleanupFailure(stage,cause(e)));}}
                    }.execute();return;
                }
                finishDownload(client,progress);
                if(JOptionPane.showConfirmDialog(client,"Версия "+release.version+" загружена и проверена.\nЗакрыть программу и установить обновление?"
                        +"\nПри закрытии TTWR предложит сохранить незавершённую работу.","Обновление готово",JOptionPane.YES_NO_OPTION)!=JOptionPane.YES_OPTION){discard(stage);return;}
                pending=stage;pendingVersion=release.version;
                client.dispatchEvent(new WindowEvent(client,WindowEvent.WINDOW_CLOSING));
                if(pending!=null){pending=null;discard(stage);message(client,"Выход отменён. Обновление не установлено; повторите проверку, когда будете готовы.");}
            }
        }.execute();
    }
    private static void finishDownload(TTRClient client,UpdateDownloadDialog progress){checking=false;progress.finish();client.setEnabled(true);client.toFront();}
    private static final class CleanupFailure extends java.io.IOException {
        CleanupFailure(Path stage,Throwable cause){super("Не удалось удалить временные файлы загрузки. Папка: "+stage,cause);}
    }
    /** Called only after the original close handler accepted exit and SQLite closed. */
    public static void onConfirmedExit() throws java.io.IOException {
        if (pending == null) return;
        ProcessBuilder helper = UpdateInstaller.helperProcess(PortablePaths.home(),pending,pendingVersion);
        helper.redirectErrorStream(true); helper.redirectOutput(pending.resolve("installer.log").toFile()); helper.start(); pending = null;
    }
    private static Throwable cause(Throwable error) { return error instanceof ExecutionException && error.getCause()!=null ? error.getCause() : error; }
    private static void discard(Path stage){try{removeStage(stage);}catch(Exception error){System.err.println("Update temporary files retained: "+error);}}
    static void removeStage(Path stage)throws java.io.IOException {
        if(!Files.exists(stage,LinkOption.NOFOLLOW_LINKS))return;
        Path root=PortablePaths.profile().resolve("updates").toAbsolutePath().normalize();stage=stage.toAbsolutePath().normalize();
        UpdateStorage.ordinary(root);UpdateStorage.ordinary(stage);
        if(!stage.getParent().equals(root)||!stage.getFileName().toString().startsWith("update-"))throw new java.io.IOException("Unexpected download directory");
        final List<Path> paths=new ArrayList<Path>();
        Files.walkFileTree(stage,new SimpleFileVisitor<Path>(){
            public FileVisitResult preVisitDirectory(Path dir,java.nio.file.attribute.BasicFileAttributes a)throws java.io.IOException{UpdateStorage.ordinary(dir);return FileVisitResult.CONTINUE;}
            public FileVisitResult visitFile(Path file,java.nio.file.attribute.BasicFileAttributes a)throws java.io.IOException{UpdateStorage.ordinary(file);paths.add(file);return FileVisitResult.CONTINUE;}
            public FileVisitResult postVisitDirectory(Path dir,java.io.IOException error)throws java.io.IOException{if(error!=null)throw error;paths.add(dir);return FileVisitResult.CONTINUE;}
        });
        // Validate the full tree before the first deletion. Do not traverse directory links.
        for(Path file:paths){UpdateStorage.ordinary(file);Files.deleteIfExists(file);}
    }
    private static void message(TTRClient client,String text) { JOptionPane.showMessageDialog(client,text,"Обновление TTWR SQLite",JOptionPane.INFORMATION_MESSAGE); }
    private static void failure(TTRClient client,Throwable error) { JOptionPane.showMessageDialog(client,"Не удалось обновить программу.\n" + cause(error).getMessage(),"Обновление TTWR SQLite",JOptionPane.ERROR_MESSAGE); }
}
