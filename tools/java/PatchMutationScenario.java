import java.nio.file.*;
import java.net.*;
public final class PatchMutationScenario{
    public static void main(String[] args)throws Exception{
        Path file=Paths.get(args[0]);BuildSqliteTTWR.loader=new URLClassLoader(new URL[]{Paths.get(args[1]).toUri().toURL(),Paths.get(args[2]).toUri().toURL()});
        Files.write(file,BuildSqliteTTWR.transform("ttr/MutationScenario.class",Files.readAllBytes(file)));
    }
}
