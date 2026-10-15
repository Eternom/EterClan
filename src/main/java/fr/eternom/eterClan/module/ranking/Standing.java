package fr.eternom.eterClan.module.ranking;

/**
 * La ligne d'un clan dans le classement du mois. score : moyenne des 4 notes sur 100, en dixièmes (875 = 87,5) ;
 * activity : minutes de jeu actif des membres ; work : quêtes et défis réussis par les membres ; treasury : argent versé
 * net dans la réserve, en centimes ; expansion : chunks gagnés net.
 */
public record Standing(long clanId, int place, long score, long activity, long work, long treasury, long expansion) {
}
