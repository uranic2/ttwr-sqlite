package ttr;
public final class SqliteFieldSetters { private SqliteFieldSetters() {}
public static void Player_bNoRating(Player o, boolean v) { o.bNoRating=v; SqliteMutations.changed(o,"bNoRating",v); }
public static void Player_deltaR(Player o, double v) { o.deltaR=v; SqliteMutations.changed(o,"deltaR",v); }
public static void Player_Place(Player o, int v) { o.Place=v; SqliteMutations.changed(o,"Place",v); }
public static void Player_PlaceNew(Player o, int v) { o.PlaceNew=v; SqliteMutations.changed(o,"PlaceNew",v); }
public static void Player_profile(Player o, Player.Profile v) { o.profile=v; SqliteMutations.changed(o,"profile",v); }
public static void Player_Rating(Player o, double v) { o.Rating=v; SqliteMutations.changed(o,"Rating",v); }
public static void Player_stats(Player o, Player.Stats v) { o.stats=v; SqliteMutations.changed(o,"stats",v); }
public static void Player_stats2(Player o, Player.Stats v) { o.stats2=v; SqliteMutations.changed(o,"stats2",v); }
public static void Player_Profile_BirthDate(Player.Profile o, String v) { o.BirthDate=v; SqliteMutations.changed(o,"BirthDate",v); }
public static void Player_Profile_bModified(Player.Profile o, boolean v) { o.bModified=v; SqliteMutations.changed(o,"bModified",v); }
public static void Player_Profile_bNew(Player.Profile o, boolean v) { o.bNew=v; SqliteMutations.changed(o,"bNew",v); }
public static void Player_Profile_Country(Player.Profile o, String v) { o.Country=v; SqliteMutations.changed(o,"Country",v); }
public static void Player_Profile_DateAdd(Player.Profile o, java.util.Calendar v) { o.DateAdd=v; SqliteMutations.changed(o,"DateAdd",v); }
public static void Player_Profile_ID(Player.Profile o, int v) { o.ID=v; SqliteMutations.changed(o,"ID",v); }
public static void Player_Profile_Name(Player.Profile o, String v) { o.Name=v; SqliteMutations.changed(o,"Name",v); }
public static void Player_Profile_Region(Player.Profile o, String v) { o.Region=v; SqliteMutations.changed(o,"Region",v); }
public static void Player_Profile_Sex(Player.Profile o, String v) { o.Sex=v; SqliteMutations.changed(o,"Sex",v); }
public static void Player_Profile_ShortName(Player.Profile o, String v) { o.ShortName=v; SqliteMutations.changed(o,"ShortName",v); }
public static void Player_Profile_Town(Player.Profile o, String v) { o.Town=v; SqliteMutations.changed(o,"Town",v); }
public static void Player_Stats_loses(Player.Stats o, int v) { o.loses=v; SqliteMutations.changed(o,"loses",v); }
public static void Player_Stats_tours(Player.Stats o, int v) { o.tours=v; SqliteMutations.changed(o,"tours",v); }
public static void Player_Stats_wins(Player.Stats o, int v) { o.wins=v; SqliteMutations.changed(o,"wins",v); }
}
