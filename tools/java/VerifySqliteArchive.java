import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import org.objectweb.asm.*;

/** Audit executable bytecode, rather than unused constants in the original constant pool. */
public final class VerifySqliteArchive {
    static final Set<String> OLD = new HashSet<>(Arrays.asList("CheckForUpdateSoft","DownloadSoft","OnCompleteSoftCheck","OnCompleteDownloadSoft"));
    public static void main(String[] args)throws Exception {
        int[] stubs={0},labels={0};
        try(JarFile jar=new JarFile(args[0])) {
            if(jar.getEntry("ttr/Updater$2.class")!=null||jar.getEntry("ttr/Updater$3.class")!=null)throw new IllegalStateException("Legacy network workers retained");
            if(!Arrays.equals(Files.readAllBytes(Paths.get(args[1])),jar.getInputStream(jar.getEntry("ttr/data/ttwr.png")).readAllBytes()))throw new IllegalStateException("Application icon differs from source asset");
            Enumeration<JarEntry> entries=jar.entries();
            while(entries.hasMoreElements()) {
                JarEntry entry=entries.nextElement();if(!entry.getName().endsWith(".class"))continue;
                ClassReader reader=new ClassReader(jar.getInputStream(entry));
                reader.accept(new ClassVisitor(Opcodes.ASM9) {
                    public MethodVisitor visitMethod(int access,String name,String desc,String signature,String[] exceptions) {
                        boolean booleanStub=entry.getName().equals("ttr/TTRClient.class")&&name.equals("DownloadSoft");
                        boolean stub=booleanStub||(entry.getName().equals("ttr/Updater.class")&&OLD.contains(name))
                                ||(entry.getName().equals("ttr/TTRClient$24.class")&&name.startsWith("OnComplete")&&name.contains("Soft"));
                        if(stub)stubs[0]++;
                        return new MethodVisitor(Opcodes.ASM9) {
                            public void visitInsn(int opcode) { if(stub&&opcode!=Opcodes.RETURN&&!(booleanStub&&(opcode==Opcodes.ICONST_0||opcode==Opcodes.IRETURN)))throw new IllegalStateException("Legacy stub has executable code: "+name); }
                            public void visitVarInsn(int opcode,int var) { if(stub)throw new IllegalStateException("Legacy stub accesses locals"); }
                            public void visitLdcInsn(Object value) { if(stub)throw new IllegalStateException("Legacy stub loads constants"); }
                            public void visitFieldInsn(int op,String owner,String field,String type) { if(stub)throw new IllegalStateException("Legacy stub accesses state"); }
                            public void visitMethodInsn(int opcode,String owner,String method,String descriptor,boolean itf) {
                                if(stub||OLD.contains(method))throw new IllegalStateException("Legacy updater call retained: "+entry.getName()+" / "+name+" -> "+owner+"."+method);
                                if(owner.equals("ttr/updates/AppVersion")&&method.equals("label"))labels[0]++;
                            }
                        };
                    }
                },ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
            }
        }
        if(stubs[0]!=7||labels[0]<2)throw new IllegalStateException("Expected legacy stubs and status version hooks missing");
        System.out.println("ARCHIVE_AUDIT_OK: no legacy software checks/downloads; 7 compatibility stubs; network workers removed; shared status version; new icon");
    }
}
