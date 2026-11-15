package ttr;
public final class MutationScenario{
    public static void edit(Player p,String name,double rating){p.profile.Name=name;p.profile.bModified=true;p.Rating=rating;p.stats.wins+=2;p.stats2.tours+=1;}
    public static void fail(Player p){p.profile.Name="MUST ROLLBACK";p.Rating=999999;throw new IllegalStateException("planned rollback");}
    public static boolean reject(Player p){p.profile.Name="MUST ALSO ROLLBACK";return false;}
    public static void rating(Player p,double rating,double delta){p.Rating=rating;p.deltaR=delta;}
    public static void name(Player p,String name){p.profile.Name=name;}
    public static void replaceStats(Player p,Player.Stats value){p.stats=value;}
    public static void wins(Player.Stats stats,int value){stats.wins=value;}
}
