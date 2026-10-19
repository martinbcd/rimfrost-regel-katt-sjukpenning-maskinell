package se.fk.github.maskinellregelkattsjukpenning.logic;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.fk.rimfrost.framework.handlaggning.model.ProduceratResultat;
import se.fk.rimfrost.framework.handlaggning.model.Yrkande;

/**
 * Läser kattens uppgifter ur yrkandets producerade resultat.
 *
 * <p>Uppgifterna registreras av ett tidigare steg i handläggningen som producerade resultat:</p>
 * <ul>
 *   <li>{@value #KATTREGISTRERING} – data är json med ägarens personnummer i fältet {@code agare}</li>
 *   <li>{@value #VETERINARINTYG} – intygets period anges av resultatets from/tom</li>
 * </ul>
 */
public class KattUppgifter
{
   public static final String KATTREGISTRERING = "KATTREGISTRERING";
   public static final String VETERINARINTYG = "VETERINARINTYG";

   private static final Logger LOGGER = LoggerFactory.getLogger(KattUppgifter.class);

   private KattUppgifter()
   {
   }

   public static boolean isKattRegistreradPaAgare(Yrkande yrkande, String personnummer, ObjectMapper objectMapper)
   {
      return yrkande.produceradeResultat().stream()
            .filter(resultat -> KATTREGISTRERING.equals(resultat.typ()))
            .anyMatch(resultat -> personnummer.equals(readAgare(resultat, objectMapper)));
   }

   public static boolean hasVeterinarintygForPerioden(Yrkande yrkande)
   {
      return yrkande.produceradeResultat().stream()
            .filter(resultat -> VETERINARINTYG.equals(resultat.typ()))
            .anyMatch(resultat -> !resultat.resultatFrom().isAfter(yrkande.yrkandeFrom())
                  && !resultat.resultatTom().isBefore(yrkande.yrkandeTom()));
   }

   private static String readAgare(ProduceratResultat resultat, ObjectMapper objectMapper)
   {
      try
      {
         var agare = objectMapper.readTree(resultat.data()).get("agare");
         return agare != null ? agare.asText() : null;
      }
      catch (JsonProcessingException e)
      {
         LOGGER.warn("Could not parse data of producerat resultat {} with typ {}", resultat.id(), resultat.typ(), e);
         return null;
      }
   }
}
