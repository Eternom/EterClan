# EterClan

Les **clans** du réseau, côté Paper : des guildes avec leurs membres et permissions, une banque, un terrain en chunks
et des parcelles à louer. Document développeur, à tenir à jour avec le code. À installer **partout** (lobbys, survies,
mondes ressources) : le tag du clan remplace le grade sur tout le réseau ; le **terrain** ne se prend que là où
`land.enabled: true` (la survie ; faux par défaut, donc sur les serveurs créés par l'orchestrateur).

Le clan existe sur **tout le réseau** (comme un home) : ses chunks se comptent partout (gratuits, prix, entretien) ;
chaque chunk et chaque parcelle restent sur le serveur et le monde où ils ont été posés.

## Prérequis

- **EterLib 1.8.0+** (`depend`) : base, Redis (invitations, bus réseau), langues, menus (cadre, Dialogs), étiquettes.
- **Vault + EterEconomy** (`softdepend`) : création payante, banque, chunks, loyers.
- **EterEssential 1.0.6+** : la perte d'argent à la mort (5 %) ne touche que le porte-monnaie, pas la banque du clan.

## Modules

- **`clan`** : un joueur seul est un clan d'une personne. Création payante (`clan.creation-price`), nom + tag (2 à 5),
  invitations gardées 5 min dans Redis, départ, exclusion, cession, dissolution (comptes rendus, réserve perdue).
  **Pas de rôles** : chaque membre a sa liste de permissions (`ClanPermission`, en base `build,doors,...`), réglée dans
  le menu par qui a `PERMISSIONS` ; le chef a tout. Défaut d'un nouveau membre : `clan.default-permissions`.
  Étiquettes EterLib : `clan` (le tag, `<tag_clan>` dans EterTab) et **`badge`** (le tag mis en forme, lang/ > `badge`),
  qui **remplace le grade** dans le Tab, le pseudo, la sidebar (EterTab) et le chat (EterChat). Pas de badge (donc le
  grade) pour le staff (`eter.display.staff`) et pour qui préfère son grade (`/clan display`, ou clic sur sa tête dans
  le menu ; colonne `show_rank`).
- **`claim`** : chunks (`eterclan_claims`, clé serveur + monde + x + z : un seul clan par chunk). Le premier chunk
  d'un clan sur un serveur se pose n'importe où, les suivants doivent toucher son terrain. Payés au serveur par la
  **réserve** (`Pricing` : `free-chunks` gratuits, puis `price` × `growth`^(n-1), plafonné à `cap` fois ; l'entretien
  hebdomadaire suit la même courbe depuis `upkeep`). Rendre un chunk : sans remboursement, pas sous une parcelle louée.
  - **Protection** (`ProtectionListener`, `Access`, tout en mémoire) : chaque action demande sa permission (blocs,
    conteneurs, portes, redstone, cultures, animaux, entités) ; le monde n'entre pas (explosions, feu, pistons,
    liquides, distributeurs, arbres, monstres qui changent des blocs) ; pas de PvP sur un terrain. Staff :
    `eterclan.bypass.claims`.
  - Action bar en changeant de terrain ou de parcelle (`LandListener`) ; bords en particules (`/clan here`).
- **`bank`** : un **compte par membre** (lui seul y touche, rendu s'il part) et la **réserve** (y donner : tout membre ;
  en prendre : `RESERVE`). Tout mouvement est une requête relative conditionnelle (jamais d'argent créé).
  **Passage hebdomadaire** (`WeeklyCycle`, vérifié toutes les 10 min sur chaque serveur, réservé en base par le
  premier) : l'**intérêt** (taux réglé par `INTEREST`, 0 à `bank.max-interest`) passe des comptes à la réserve ;
  l'**entretien** est payé par la réserve, puis les comptes au prorata ; sinon les chunks les plus récents sont perdus
  (et leurs parcelles). Les **loyers** sont encaissés à leur date, sur le porte-monnaie du locataire, dans la réserve.
- **`zone`** : parcelles (`eterclan_zones`) = rectangles au bloc près sur toute la hauteur, dans le terrain du clan
  (`pos1`/`pos2` = bloc visé), sans chevauchement, `zones.max-area` blocs au plus. `ZONES` les crée, supprime, met en
  location (loyer par semaine). Un membre la loue (première semaine payée tout de suite). **Louée, elle n'obéit plus
  aux permissions du clan** : seuls le locataire et ceux qu'il a choisis (`trust`) y agissent ; `ZONES` ne fait que gérer
  (fin de location), jamais accéder. Une parcelle louée ne se supprime pas ; un membre qui part perd ses locations.
- **`sync`** (`ClanSync`) : clans, chunks et parcelles utiles à ce serveur, en mémoire ; relus après chaque changement
  puis annoncés aux autres serveurs (bus réseau `eterclan`).
- **`menu`** : `/clan` (le clan, la banque avec saisie dans des Dialogs, le terrain), les membres, leurs permissions.

## Commandes et permissions

`/clan` (alias `/clans`, `/guild`) : menu ; `create <nom> <TAG>`, `invite`, `accept`, `leave`, `kick`, `transfer`,
`disband confirm`, `claim`, `unclaim`, `here`, `deposit`, `withdraw`, `give`, `take`, `interest <taux>`, `display`,
`zone pos1|pos2|create|delete|rent|take|end|trust|untrust|list`.

| Permission | Par défaut | Rôle |
|---|---|---|
| `eterclan.use` | tous | `/clan` |
| `eter.display.staff` | non | Le grade reste affiché à la place du tag du clan (à donner aux grades staff) |
| `eterclan.bypass.claims` | op | Agir partout (staff) |
| `eterclan.admin` | op | Tout |

## Plus tard

Guerre et points entre clans (les terrains resteront hors d'atteinte) : pas encore discutés.
