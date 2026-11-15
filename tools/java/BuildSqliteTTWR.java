import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;
import org.objectweb.asm.*;

public final class BuildSqliteTTWR implements Opcodes {
    static final Set<String> PLAYER_FIELDS=new HashSet<String>(Arrays.asList("profile","Place","PlaceNew","Rating","deltaR","stats","stats2","bNoRating"));
    static final Set<String> PROFILE_FIELDS=new HashSet<String>(Arrays.asList("ID","Name","ShortName","Country","Region","Town","BirthDate","Sex","DateAdd","bModified","bNew"));
    static boolean business(String owner,String name){return owner.equals("ttr/Player")?PLAYER_FIELDS.contains(name):owner.equals("ttr/Player$Profile")?PROFILE_FIELDS.contains(name):owner.equals("ttr/Player$Stats")&&Arrays.asList("tours","wins","loses").contains(name);}
    static final List<String> log=new ArrayList<String>();
    static ClassLoader loader;
    static String projectVersion;
    static byte[] transform(String entry,byte[] bytes){
        ClassReader reader=new ClassReader(bytes);final Set<String> mutators=new HashSet<String>();final int[] writes={0},paths={0};final boolean[] environment={false};
        reader.accept(new ClassVisitor(ASM9){public MethodVisitor visitMethod(int access,String name,String desc,String sig,String[] ex){return new MethodVisitor(ASM9){public void visitFieldInsn(int op,String owner,String field,String type){if(op==PUTFIELD&&business(owner,field))mutators.add(name+desc);}public void visitMethodInsn(int op,String owner,String method,String descriptor,boolean itf){if(owner.equals("java/lang/System")&&method.equals("getenv")&&descriptor.equals("(Ljava/lang/String;)Ljava/lang/String;"))environment[0]=true;}};}},ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
        boolean tournament=entry.equals("ttr/Tournament.class"),rating=entry.equals("ttr/RatingList.class"),client=entry.equals("ttr/TTRClient.class"),settings=entry.equals("ttr/Settings.class");
        boolean legacyUpdater=entry.equals("ttr/Updater.class"),legacyCallbacks=entry.equals("ttr/TTRClient$24.class");
        if(mutators.isEmpty()&&!tournament&&!rating&&!client&&!settings&&!legacyUpdater&&!legacyCallbacks&&!environment[0]&&!entry.equals("ttr/DBTempls.class"))return bytes;
        ClassWriter writer=new ClassWriter(reader,ClassWriter.COMPUTE_FRAMES|ClassWriter.COMPUTE_MAXS){
            protected String getCommonSuperClass(String a,String b){try{
                Class<?> x=Class.forName(a.replace('/','.'),false,loader),y=Class.forName(b.replace('/','.'),false,loader);
                if(x.isAssignableFrom(y))return a;if(y.isAssignableFrom(x))return b;if(x.isInterface()||y.isInterface())return "java/lang/Object";
                do{x=x.getSuperclass();}while(!x.isAssignableFrom(y));return x.getName().replace('.','/');
            }catch(Exception error){throw new IllegalStateException("Cannot resolve frames: "+a+" / "+b,error);}}
        };
        reader.accept(new ClassVisitor(ASM9,writer){
            public FieldVisitor visitField(int access,String name,String desc,String sig,Object value){
                if(client&&name.equals("version")&&desc.equals("Ljava/lang/String;"))value=projectVersion;
                return super.visitField(access,name,desc,sig,value);
            }
            public MethodVisitor visitMethod(int access,String name,String desc,String sig,String[] exceptions){
                MethodVisitor target=super.visitMethod(access,name,desc,sig,exceptions);
                if(client&&name.equals("UpdateDB")&&desc.equals("(Z)Z")){
                    target.visitCode();target.visitVarInsn(ALOAD,0);target.visitVarInsn(ILOAD,1);
                    target.visitMethodInsn(INVOKESTATIC,"ttr/PlayerUpdates","update","(Lttr/TTRClient;Z)Z",false);
                    target.visitInsn(IRETURN);target.visitMaxs(2,2);target.visitEnd();return null;
                }
                if(legacyUpdater&&name.equals("CheckForUpdateDB")&&desc.equals("()V")){
                    target.visitCode();target.visitVarInsn(ALOAD,0);
                    target.visitMethodInsn(INVOKESTATIC,"ttr/DatabaseStatus","check","(Lttr/Updater;)V",false);
                    target.visitInsn(RETURN);target.visitMaxs(1,1);target.visitEnd();return null;
                }
                if(client&&name.equals("DownloadSoft")&&desc.equals("()Z")){
                    target.visitCode();target.visitInsn(ICONST_0);target.visitInsn(IRETURN);target.visitMaxs(1,1);target.visitEnd();return null;
                }
                if((legacyUpdater&&Arrays.asList("CheckForUpdateSoft","DownloadSoft","OnCompleteSoftCheck","OnCompleteDownloadSoft").contains(name))
                        ||(legacyCallbacks&&Arrays.asList("OnCompleteSoftCheck","OnCompleteDownloadSoft").contains(name))){
                    target.visitCode();target.visitInsn(RETURN);target.visitMaxs(0,8);target.visitEnd();return null;
                }
                if(client&&name.equals("jStatusSoftMouseClicked")&&desc.equals("(Ljava/awt/event/MouseEvent;)V")){
                    target.visitCode();target.visitVarInsn(ALOAD,0);target.visitMethodInsn(INVOKESTATIC,"ttr/updates/ProgramUpdates","checkManually","(Lttr/TTRClient;)V",false);target.visitInsn(RETURN);target.visitMaxs(1,2);target.visitEnd();return null;
                }
                boolean batch=mutators.contains(name+desc)&&!name.startsWith("<")&&!entry.startsWith("ttr/Player");
                boolean history=rating&&Arrays.asList("LoadXML","RecalcRating","ApplyToPlayersDB","AddTournament","RemoveTournament","SetRatingOverride","ClearRatingOverride","FixRatingList").contains(name);
                boolean update=client&&name.equals("UpdateDB");
                return new MethodVisitor(ASM9,target){
                    final Label start=new Label(),end=new Label(),handler=new Label();
                    public void visitCode(){super.visitCode();if(batch)super.visitMethodInsn(INVOKESTATIC,"ttr/SqliteMutations","beginBatch","()V",false);if(update){super.visitVarInsn(ALOAD,0);super.visitMethodInsn(INVOKESTATIC,"ttr/SqliteUpdates","begin","(Lttr/TTRClient;)V",false);}if(batch||update)super.visitLabel(start);}
                    public void visitFieldInsn(int op,String owner,String field,String type){
                        if(update&&op==PUTFIELD&&owner.equals("ttr/TTRClient")&&field.equals("mainDB")){
                            super.visitInsn(DUP2);super.visitFieldInsn(op,owner,field,type);super.visitMethodInsn(INVOKESTATIC,"ttr/SqliteUpdates","remember","(Lttr/TTRClient;Lttr/DBMain;)V",false);
                        }else if(op==PUTFIELD&&business(owner,field)){
                            String setter=owner.substring(4).replace('$','_')+"_"+field;
                            super.visitMethodInsn(INVOKESTATIC,"ttr/SqliteFieldSetters",setter,"(L"+owner+";"+type+")V",false);writes[0]++;
                        }else super.visitFieldInsn(op,owner,field,type);
                    }
                    public void visitMethodInsn(int op,String owner,String method,String descriptor,boolean itf){
                        if(owner.equals("ttr/Updater")&&method.equals("CheckForUpdateSoft")&&descriptor.equals("()V")){
                            super.visitInsn(POP);
                        }else if(owner.equals("java/lang/System")&&method.equals("getenv")&&descriptor.equals("(Ljava/lang/String;)Ljava/lang/String;")){
                            super.visitMethodInsn(INVOKESTATIC,"ttr/PortablePaths","getenv",descriptor,false);paths[0]++;
                        }else if(tournament&&name.equals("SaveXML_Players")&&owner.equals("ttr/DBPlayers")&&method.equals("GetArray")){
                            super.visitVarInsn(ALOAD,0);super.visitMethodInsn(INVOKESTATIC,"ttr/SqlitePlayersIntegration","exportPlayers","(Lttr/DBPlayers;Lttr/Tournament;)[Lttr/Player;",false);
                        }else if(owner.equals("ttr/Utility")&&method.equals("LoadXMLDocument")&&descriptor.equals("(Ljava/lang/String;Lttr/events/XmlLoadEvents;)Lorg/w3c/dom/Document;")){super.visitMethodInsn(INVOKESTATIC,"ttr/PlayersUpdateControl","document",descriptor,false);}else super.visitMethodInsn(op,owner,method,descriptor,itf);
                    }
                    public void visitLdcInsn(Object value){
                        if(client&&"v 1.4.2.1".equals(value)){super.visitMethodInsn(INVOKESTATIC,"ttr/updates/AppVersion","label","()Ljava/lang/String;",false);return;}
                        if(environment[0]&&value instanceof String)value=((String)value).replace("\\TTWR\\","\\data\\");
                        if(client&&value instanceof String){String text=(String)value;if(text.equals("В базу данных были внесены изменения. Сохранить?"))value="Изменения игроков уже сохранены.\nСохранить остальные изменения базы?";else if(text.startsWith("Требуется обновление базы данных")&&text.endsWith("Сохранить?"))value="Изменения игроков уже сохранены.\nПеред обновлением сохранить остальные изменения базы?";}
                        super.visitLdcInsn(value);
                    }
                    public void visitInsn(int opcode){
                        if(opcode>=IRETURN&&opcode<=RETURN){
                            if(client&&name.equals("<init>")&&desc.equals("(Lttr/Settings;)V")&&opcode==RETURN){
                                super.visitVarInsn(ALOAD,0);super.visitFieldInsn(GETFIELD,"ttr/TTRClient","jStatusSoft","Ljavax/swing/JLabel;");super.visitMethodInsn(INVOKESTATIC,"ttr/updates/AppVersion","label","()Ljava/lang/String;",false);super.visitMethodInsn(INVOKEVIRTUAL,"javax/swing/JLabel","setText","(Ljava/lang/String;)V",false);
                                super.visitVarInsn(ALOAD,0);super.visitMethodInsn(INVOKESTATIC,"ttr/updates/ProgramUpdates","attach","(Lttr/TTRClient;)V",false);
                                super.visitVarInsn(ALOAD,0);super.visitMethodInsn(INVOKESTATIC,"ttr/PlayerUpdates","attach","(Lttr/TTRClient;)V",false);
                            }
                            if(settings&&name.equals("Load")&&desc.equals("(Ljava/lang/String;)Z")&&opcode==IRETURN){super.visitVarInsn(ALOAD,0);super.visitMethodInsn(INVOKESTATIC,"ttr/PortablePaths","afterLoad","(ZLttr/Settings;)Z",false);}
                            if(client&&name.equals("formWindowClosing")&&desc.equals("(Ljava/awt/event/WindowEvent;)V")&&opcode==RETURN){super.visitVarInsn(ALOAD,0);super.visitMethodInsn(INVOKESTATIC,"ttr/SqliteLifecycle","onWindowClosing","(Lttr/TTRClient;)V",false);}
                            if(update){super.visitVarInsn(ALOAD,0);super.visitMethodInsn(INVOKESTATIC,"ttr/SqliteUpdates","finish","(Lttr/TTRClient;)V",false);}
                            if(history){super.visitVarInsn(ALOAD,0);super.visitMethodInsn(INVOKESTATIC,"ttr/SqliteMutations","historyChanged","(Lttr/RatingList;)V",false);}
                            if(batch){if(opcode==IRETURN&&desc.endsWith(")Z"))super.visitInsn(DUP);else super.visitInsn(ICONST_1);super.visitMethodInsn(INVOKESTATIC,"ttr/SqliteMutations","endBatch","(Z)V",false);}
                        }
                        super.visitInsn(opcode);
                    }
                    public void visitMaxs(int stack,int locals){
                        if(batch||update){super.visitLabel(end);super.visitTryCatchBlock(start,end,handler,"java/lang/Throwable");super.visitLabel(handler);if(batch){super.visitInsn(ICONST_0);super.visitMethodInsn(INVOKESTATIC,"ttr/SqliteMutations","endBatch","(Z)V",false);}if(update){super.visitVarInsn(ALOAD,0);super.visitMethodInsn(INVOKESTATIC,"ttr/SqliteUpdates","abort","(Lttr/TTRClient;)V",false);}super.visitInsn(ATHROW);}
                        super.visitMaxs(stack,locals);
                    }
                };
            }
        },ClassReader.EXPAND_FRAMES);
        log.add(entry+": intercepted field writes="+writes[0]+", batch methods="+mutators.size()+", portable environment calls="+paths[0]);return writer.toByteArray();
    }
    public static void main(String[] args)throws Exception{
        Path source=Paths.get(args[0]),classes=Paths.get(args[1]),output=Paths.get(args[2]);
        byte[] icon=Files.readAllBytes(Paths.get(args[6]));
        String version=Files.readString(Paths.get(args[5])).trim();
        if(!version.matches("[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?"))throw new IllegalArgumentException("Invalid project VERSION");
        projectVersion=version;
        loader=new URLClassLoader(new URL[]{classes.toUri().toURL(),source.toUri().toURL()},BuildSqliteTTWR.class.getClassLoader());
        Set<String> replacement=new HashSet<String>();
        try(var files=Files.walk(classes)){files.filter(p->p.toString().endsWith(".class")).forEach(p->{String name=classes.relativize(p).toString().replace('\\','/');if(name.startsWith("ttr/"))replacement.add(name);});}
        try(JarFile input=new JarFile(source.toFile());ZipOutputStream out=new ZipOutputStream(Files.newOutputStream(output))){
            Manifest manifest=input.getManifest();String cp=manifest.getMainAttributes().getValue("Class-Path");
            manifest.getMainAttributes().putValue("Class-Path",(cp==null?"":cp+" ")+"lib/"+args[3]+" lib/slf4j-api-1.7.36.jar lib/slf4j-nop-1.7.36.jar");
            manifest.getMainAttributes().putValue("Enable-Native-Access","ALL-UNNAMED");
            manifest.getMainAttributes().putValue("Implementation-Version",version);
            ByteArrayOutputStream buffer=new ByteArrayOutputStream();manifest.write(buffer);out.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));out.write(buffer.toByteArray());out.closeEntry();
            Enumeration<JarEntry> entries=input.entries();
            while(entries.hasMoreElements()){
                JarEntry e=entries.nextElement();String name=e.getName();
                if(name.equals("META-INF/MANIFEST.MF")||name.equals("ttr/build-version.txt")||name.equals("ttr/data/ttwr.png")||name.equals("ttr/Updater$2.class")||name.equals("ttr/Updater$3.class")||replacement.contains(name)||name.startsWith("ttr/UI/PlayersListPanel$")||name.startsWith("ttr/UI/AboutDlg$")||name.startsWith("ttr/UI/DBUpdateDlg$"))continue;
                if(name.matches("(?i)META-INF/.*\\.(SF|RSA|DSA|EC)"))throw new IllegalStateException("Signed archive");
                byte[] bytes;try(InputStream s=input.getInputStream(e)){bytes=s.readAllBytes();}
                if(name.endsWith(".class")&&name.startsWith("ttr/")&&(!name.startsWith("ttr/Streaming")||name.equals("ttr/StreamingPlayers.class"))&&!name.startsWith("ttr/LoadPerformance"))bytes=transform(name,bytes);
                out.putNextEntry(new ZipEntry(name));out.write(bytes);out.closeEntry();
            }
            for(String name:new TreeSet<String>(replacement)){out.putNextEntry(new ZipEntry(name));out.write(Files.readAllBytes(classes.resolve(name)));out.closeEntry();}
            out.putNextEntry(new ZipEntry("ttr/build-version.txt"));out.write((version+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));out.closeEntry();
            out.putNextEntry(new ZipEntry("ttr/data/ttwr.png"));out.write(icon);out.closeEntry();
        }
        Files.write(Paths.get(args[4]),log);System.out.println("SQLite archive built; transformed classes="+log.size()+"; replacement/helper classes="+replacement.size());
    }
}
