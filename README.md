# EterClan

Les **clans** du réseau, côté Paper : des guildes avec leurs membres et permissions, une banque, un terrain en chunks
et des parcelles à louer. Document développeur, à tenir à jour avec le code. À installer **partout** (lobbys, survies,
mondes ressources) : le tag du clan remplace le grade sur tout le réseau ; le **terrain** ne se prend que là où
`land.enabled: true` (la survie ; faux par défaut, donc sur les serveurs créés par l'orchestrateur).

Le clan existe sur **tout le réseau** (comme un home) : ses chunks se comptent partout (gratuits, prix, entretien) ;
chaque chunk et chaque parcelle restent sur le serveur et le monde où ils ont été posés.

## Prérequis

- **EterLib 1.15.0+** (`depend`) : base, Redis (invitations, bus réseau), langues, menus (cadre, Dialogs), étiquettes.
- **EterEconomy 2.2.2+** (`softdepend`, son API `EconomyApi`) : création payante, banque, chunks, loyers.
- **EterEssential 1.0.6+** : la perte d'argent à la mort (5 %) ne touche que le porte-monnaie, pas la banque du clan.

## Modules

- **`clan`** : un joueur seul est un clan d'une personne. Création payante (`clan.creation-price`), nom + tag (2 à 5),
  invitations gardées 5 min dans Redis, départ, exclusion, cession, dissolution (comptes rendus, réserve perdue).
  **Pas de rôles** : chaque membre a sa liste de permissions (`ClanPermission`, en base `build,doors,...`), réglée dans
  le menu par qui a `PERMISSIONS` ; le chef a tout. Défaut d'un nouveau membre : `clan.default-permissions`.
  `HOMES` (1.4.0) : avoir des homes sur le terrain ; donnée une fois à tous les membres déjà là (table `eterclan_upgrades`).
  Étiquettes EterLib : `clan` (le tag, `<tag_clan>` dans EterTab) et **`badge`** (le tag mis en forme, lang/ > `badge`),
  qui **remplace le grade** dans le Tab, le pseudo, la sidebar (EterTab) et le chat (EterChat). Pas de badge (donc le
  grade) pour le staff (`eter.display.staff`) et pour qui préfère son grade (clic sur sa tête dans le menu ; colonne
  `show_rank`).
- **`claim`** (refait en 1.7.0) : chunks (`eterclan_claims`, clé serveur + monde + x + z : un seul clan par chunk ;
  `paid` : ce qu'il a coûté). La forme du terrain se calcule sans Bukkit (`LandPlan`, testé) :
  - le **cœur** : le premier chunk devient le **chunk primaire** (colonnes `heart_*` du clan), et le carré de 3 × 3
    autour est pris avec lui, **gratuit et sans entretien**. Il ne se rend pas en morceaux (le chef rend tout le terrain) ;
  - les **extensions** : reliées au cœur (4 voisins, même serveur et monde), payées tout de suite par la réserve
    (`Pricing` : `price` × `growth`^(n-1), plafonné à `cap` fois) ;
  - les **avant-postes** : des groupes de chunks posés ailleurs, sans lien avec la base, `outpost-factor` fois plus
    chers ; au plus `outpost-size` chunks chacun, et autant que le **niveau** du clan le permet (`LandRules#levels` :
    paliers de membres et de chunks, +1 pour le top 3 du mois) ;
  - pas de chunk dans `land.forbidden`, ni à moins de `land.buffer` chunks d'un autre clan ;
  - rendre un chunk : jamais en coupant la base ou un avant-poste, `refund` (25 %) de son prix revient ;
  - le cœur se déplace sur un chunk de la base (`heart-move-price`, une fois tous les `heart-move-days` jours) ;
  - **carte** du terrain (`MapMenu`, 9 × 5 chunks, couleurs par sorte, plusieurs chunks pris d'un coup avec le prix de
    chacun et le total), bords en particules (or : cœur, vert : base, bleu : avant-poste, rouge : un autre clan) ;
  - **home du clan** : `/clan sethome` (permission CLAIM, sur son terrain), `/clan home` (EterLib : combat, attente, délai).
  - **Protection** (`ProtectionListener`, `Access`, tout en mémoire) : chaque action demande sa permission (blocs,
    conteneurs, portes, redstone, cultures, animaux, entités, supports d'armure, laisses, véhicules, canne à pêche,
    potions lancées) ; le monde n'entre pas (explosions, feu, pistons, liquides, distributeurs, arbres, monstres qui
    changent des blocs ou piétinent, flèches sur les boutons et les blocs fragiles, éclair d'un trident) ; pas de PvP sur
    un terrain. **Tag en couleur** (1.6.0, `TagColor`). **Combat, explosions et feu** sont protégés par défaut, mais un
    clan peut les **autoriser** sur son terrain (`ClanRule`). Staff : `eterclan.bypass.claims`.
  - **Accès** (1.7.0, `Access`, `AccessService`, permission `ACCESS`) : le terrain est **ouvert**, **fermé** (seuls les
    membres et les invités entrent) ou **à pass** (un pass s'achète : prix et durée choisis par le clan, payé dans la
    réserve, droits choisis parmi portes, redstone, cultures, animaux, coffres ; jamais construire). Un panneau
    `[Pass]` devient un guichet. Les invités (INVITE) ont un accès gratuit de 1 h à 7 jours ; la **liste noire**
    n'entre nulle part. Les passes se retirent (le reste est rendu au prorata). On est repoussé au bord d'un endroit
    interdit, et aucune téléportation n'y mène (perle, chorus, tpa) ; un titre annonce le terrain d'un autre clan.
  - **Journal** (`Journal`, `eterclan_journal`, 90 jours) : les actions du clan et les mouvements de la réserve.
- **`bank`** : un **compte par membre** (lui seul y touche, rendu s'il part) et la **réserve** (y donner : tout membre qui a
  `bank.min-give-playtime-hours` (5) heures de jeu actif, `GiveGate`, source `playtime_total` d'EterSuccess lue par
  EterLib : un second compte ne peut pas y verser ses récompenses pour le principal ; en prendre : `RESERVE`). Tout mouvement est une requête relative conditionnelle (jamais d'argent créé).
  **Passage hebdomadaire** (`WeeklyCycle`, vérifié toutes les 10 min sur chaque serveur, réservé en base par le
  premier) : l'**intérêt** (taux réglé par `INTEREST`, 0 à `bank.max-interest`) passe des comptes à la réserve ;
  l'**entretien** (cœur gratuit, extensions sur `upkeep-growth`, avant-postes plus chers, réduit pour le top 3 du mois)
  est payé par la réserve (et les comptes si `upkeep-from-accounts`) ; sinon une **dette**, et après `debt-grace-days`
  jours des chunks perdus (`LandPlan#lossOrder` : avant-postes, puis les extensions les plus loin du cœur sans couper la
  base ; jamais le cœur), leurs parcelles aussi (un locataire est remboursé du reste de sa semaine). Rappels : 3 jours
  avant si la réserve ne suffit pas, puis 7, 3 et 1 jour(s) avant la perte. Clan inactif : chunks payants libérés après
  `inactive-paid-days`, tout après `inactive-all-days` (le staff prévenu avant). Les **loyers** sont encaissés à leur
  date, sur le porte-monnaie du locataire, dans la réserve ; rappel 2 jours avant ; impayé : une semaine de grâce.
  Les rappels partent seulement du serveur qui a le terrain (`land.enabled`). **Rôles** prêts à l'emploi :
  `clan.roles` (fiche d'un membre).
- **`zone`** : parcelles (`eterclan_zones`) = rectangles au bloc près sur toute la hauteur, dans le terrain du clan
  (deux blocs cliqués dans le monde après « Nouvelle parcelle » : `ZoneSelection`, aperçu en particules,
  boutons du chat Valider / Recommencer / Annuler, abandon après 3 min), sans chevauchement, `zones.max-area` blocs au plus. `ZONES` les crée, supprime, met en
  location (loyer par semaine). Un membre la loue (première semaine payée tout de suite). **Louée, elle n'obéit plus
  aux permissions du clan** : seuls le locataire et ceux qu'il a choisis (`trust`) y agissent ; `ZONES` ne fait que gérer
  (fin de location), jamais accéder. Une parcelle louée ne se supprime pas ; un membre qui part perd ses locations.
  **1.7.0** : chaque parcelle a un **mode d'accès** (comme le terrain, **réservée** : seuls ceux qu'elle a choisis y
  agissent, un coffre d'officiers ; **fermée** ; **à pass**, avec son propre prix), et peut être **louée à tout le monde**
  (`public_rent`, panneau `[Louer]` dedans : un guichet de location pour les joueurs hors du clan).
- **`sync`** (`ClanSync`) : clans, chunks et parcelles utiles à ce serveur, en mémoire ; relus après chaque changement
  puis annoncés aux autres serveurs (bus réseau `eterclan`).
- **`ranking`** (`ClanLeague`, `LeagueStore`) : le **classement des clans du mois**, comme le général des joueurs : 4
  critères, dans chacun une note sur 100 selon la place, le score est leur moyenne (en dixièmes). **Activité** : minutes
  de jeu actif des membres ; **travail** : quêtes et défis des membres (sources `monthly_*` d'EterReward, lues par
  EterLib) ; **trésor** : versé moins repris dans la réserve (`eterclan_monthly`, en centimes) ; **expansion** : chunks
  pris moins rendus ou perdus. En net : donner puis reprendre, ou prendre puis rendre des chunks gratuits, ne rapporte
  rien. Calculé toutes les 5 min là où `land.enabled` (la survie), photographié dans `eterclan_league`, lu partout ; le
  mois fini, le top 3 reçoit `ranking.rewards` dans sa réserve, une seule fois (`eterclan_league_rewards`), et ses
  membres sont prévenus. Offert à `/classement` d'EterReward (source `clans`, « Clans du mois »).
- **`menu`** : **tout se fait dans l'interface** (`/clan` est la seule commande). Sans clan : expliquer, créer (fenêtre
  avec nom, tag et prix affiché avant de payer), accepter une invitation. Avec : un accueil (moi, le clan, sa place du
  mois) et des sous-menus : **banque** (le don à la réserve affiche le temps de jeu qui manque), **membres** (inviter,
  fiche : permissions, exclure, céder), **terrain** (prendre, voir, rendre, parcelles : créer, louer, accès, loyer,
  supprimer), **classement** (les 4 critères du clan, le top 5, les prix) et **règles du territoire** (`RulesMenu` : ce
  que le joueur peut faire sur le terrain du clan, ce qu'il peut faire là où il se tient, calculé par `Access` donc
  parcelle louée comprise, les protections toujours actives, et la règle des parcelles qui passent avant le clan). Les
  saisies et les confirmations passent par des Dialogs ; Annuler rouvre le menu d'où l'on vient.

## Commandes et permissions

`/clan home` · `/clan sethome` : le home du clan. Staff (`eterclan.admin`) : `/clan admin info <clan>`, `/clan admin unclaim`
(le chunk où l'on est, quel que soit son clan), `/clan admin transfer <clan> <membre>`.

`/clan` (alias `/clans`, `/guild`) : le menu, seule commande.

| Permission | Par défaut | Rôle |
|---|---|---|
| `eterclan.use` | tous | `/clan` |
| `eter.display.staff` | non | Le grade reste affiché à la place du tag du clan (à donner aux grades staff) |
| `eterclan.bypass.claims` | op | Agir partout (staff) |
| `eterclan.admin` | op | Tout |

## Plus tard

Guerre et points entre clans (les terrains resteront hors d'atteinte) : pas encore discutés.

## API (pour les autres plugins)

`fr.eternom.eterClan.api.ClanApi` (`ClanApi.get()`) : personne d'autre ne lit les tables `eterclan_*`.

- `clanOf(uuid)` : le clan d'un joueur, connecté ou non, ses membres et son territoire (`chunks`) (bloquant) ;
- `isClaimed(position)`, `canBuild(joueur, position)`, `canHome(joueur, position)` (1.4.0, permission `homes` : EterHome refuse un
  home sur le terrain d'un autre clan) : la protection du terrain, en mémoire (thread principal) ;
- `openMenu(joueur)` : le menu `/clan` ;
- source de classement `clans` (EterLib `RankingSource`) : les clans par territoire, pour `/classement` d'EterReward.

L'argent passe par l'API d'EterEconomy, avec sa source : « EterClan · clan » (création, comptes rendus), « · banque »,
« · loyer ».
