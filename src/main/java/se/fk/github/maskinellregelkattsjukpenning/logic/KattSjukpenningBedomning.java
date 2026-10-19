package se.fk.github.maskinellregelkattsjukpenning.logic;

import se.fk.rimfrost.framework.regel.Utfall;

/**
 * Resultatet av regelns villkor. Alla villkor måste vara uppfyllda för utfall JA,
 * annars går ärendet till utredning.
 *
 * @param kattRegistrerad Katten är registrerad med den sökande som ägare
 * @param veterinarintyg Det finns ett sjukintyg från veterinär som täcker yrkandets period
 * @param agareForsakrad Ägaren har en arbetsgivare
 */
public record KattSjukpenningBedomning(boolean kattRegistrerad,boolean veterinarintyg,boolean agareForsakrad){public Utfall utfall(){return kattRegistrerad&&veterinarintyg&&agareForsakrad?Utfall.JA:Utfall.UTREDNING;}}
