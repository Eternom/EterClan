package fr.eternom.eterClan.module.clan;

import fr.eternom.eterClan.api.ClanApi;
import fr.eternom.eterClan.module.claim.Access;
import fr.eternom.eterClan.module.menu.ClanGui;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.UUID;

/** L'API d'EterClan (ClanApi) : le plugin lui-même, vu de l'extérieur. */
public class ClanApiService implements ClanApi {

    private final ClanRepository clans;
    private final Access access;
    private final ClanGui gui;

    public ClanApiService(ClanRepository clans, Access access, ClanGui gui) {
        this.clans = clans;
        this.access = access;
        this.gui = gui;
    }

    @Override
    public Optional<ClanInfo> clanOf(UUID player) {
        return clans.byMember(player).map(clan -> new ClanInfo(clan.id(), clan.name(), clan.tag(), clan.owner(),
                clan.members().stream().map(Clan.Member::uuid).toList()));
    }

    @Override
    public boolean isClaimed(Location location) {
        return access.isClaimed(location);
    }

    @Override
    public boolean canBuild(Player player, Location location) {
        return access.allows(player, location, ClanPermission.BUILD);
    }

    @Override
    public void openMenu(Player player) {
        gui.open(player);
    }
}
