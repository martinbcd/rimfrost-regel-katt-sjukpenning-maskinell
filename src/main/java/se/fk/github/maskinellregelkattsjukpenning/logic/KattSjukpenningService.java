package se.fk.github.maskinellregelkattsjukpenning.logic;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.OffsetDateTime;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.fk.rimfrost.adapter.arbetsgivare.ArbetsgivareAdapter;
import se.fk.rimfrost.adapter.arbetsgivare.dto.ArbetsgivareResponse;
import se.fk.rimfrost.adapter.arbetsgivare.dto.ImmutableArbetsgivareRequest;
import se.fk.rimfrost.adapter.arbetsgivare.exception.ArbetsgivareErrorCode;
import se.fk.rimfrost.adapter.arbetsgivare.exception.ArbetsgivareException;
import se.fk.rimfrost.adapter.folkbokford.FolkbokfordAdapter;
import se.fk.rimfrost.adapter.folkbokford.FolkbokfordException;
import se.fk.rimfrost.adapter.folkbokford.dto.FolkbokfordResponse;
import se.fk.rimfrost.adapter.folkbokford.dto.ImmutableFolkbokfordRequest;
import se.fk.rimfrost.framework.handlaggning.model.ImmutableHandlaggningUpdate;
import se.fk.rimfrost.framework.handlaggning.model.ImmutableUppgift;
import se.fk.rimfrost.framework.handlaggning.model.Underlag;
import se.fk.rimfrost.framework.regel.RegelErrorInformation;
import se.fk.rimfrost.framework.regel.logic.RegelUtils;
import se.fk.rimfrost.framework.regel.maskinell.logic.RegelMaskinellServiceInterface;
import se.fk.rimfrost.framework.regel.maskinell.logic.dto.ImmutableRegelMaskinellErrorResult;
import se.fk.rimfrost.framework.regel.maskinell.logic.dto.ImmutableRegelMaskinellSuccessResult;
import se.fk.rimfrost.framework.regel.maskinell.logic.dto.RegelMaskinellRequest;
import se.fk.rimfrost.framework.regel.maskinell.logic.dto.RegelMaskinellResult;
import se.fk.rimfrost.framework.regel.maskinell.logic.helpers.retry.Result;
import se.fk.rimfrost.framework.regel.maskinell.logic.helpers.retry.RetriesExhaustedException;
import se.fk.rimfrost.framework.regel.maskinell.logic.helpers.retry.RetryUtil;

@ApplicationScoped
public class KattSjukpenningService implements RegelMaskinellServiceInterface
{

   private static final Logger LOGGER = LoggerFactory.getLogger(KattSjukpenningService.class);

   @Inject
   ObjectMapper objectMapper;

   @Inject
   FolkbokfordAdapter folkbokfordAdapter;

   @Inject
   ArbetsgivareAdapter arbetsgivareAdapter;

   @ConfigProperty(name = "rimfrost.framework.regel.maskinell.retry.intervals")
   List<Integer> retryIntervals;

   @Override
   public RegelMaskinellResult processRegel(RegelMaskinellRequest regelRequest)
   {
      var handlaggning = regelRequest.handlaggning();
      var yrkande = handlaggning.yrkande();

      LOGGER.info("Started process regel for yrkandeId: {}", yrkande.id());

      // Den sökande är kattens ägare
      var personnummer = yrkande.individYrkandeRoller().getFirst().individ().varde();

      FolkbokfordResponse folkbokfordResponse;
      try
      {
         folkbokfordResponse = RetryUtil.getWithRetries(() -> getFolkbokfordResponse(personnummer), retryIntervals);
      }
      catch (RetriesExhaustedException e)
      {
         LOGGER.error("Failed to read folkbokford response", e);
         return createErrorResult(RegelFelkod.KATT_SJUKPENNING_MASKINELL_FOLKBOKFORING_READ_ERROR,
               "Failed to read folkbokford response", regelRequest);
      }

      ArbetsgivareResponse arbetsgivareResponse;
      try
      {
         arbetsgivareResponse = RetryUtil.getWithRetries(() -> getArbetsgivareResponse(personnummer), retryIntervals);
      }
      catch (RetriesExhaustedException e)
      {
         LOGGER.error("Failed to read arbetsgivare response", e);
         return createErrorResult(RegelFelkod.KATT_SJUKPENNING_MASKINELL_ARBETSGIVARE_READ_ERROR,
               "Failed to read arbetsgivare response", regelRequest);
      }

      var bedomning = new KattSjukpenningBedomning(
            KattUppgifter.isKattRegistreradPaAgare(yrkande, personnummer, objectMapper),
            KattUppgifter.hasVeterinarintygForPerioden(yrkande),
            arbetsgivareResponse != null && arbetsgivareResponse.organisationsnummer() != null);

      List<Underlag> underlag;
      try
      {
         underlag = List.of(
               RegelUtils.createUnderlag("Folkbokförd", 1, folkbokfordResponse, objectMapper),
               RegelUtils.createUnderlag("Arbetsgivare", 1, arbetsgivareResponse, objectMapper),
               RegelUtils.createUnderlag("KattSjukpenningBedomning", 1, bedomning, objectMapper));
      }
      catch (InternalError e)
      {
         LOGGER.error("Failed to create underlag", e);
         return createErrorResult(RegelFelkod.KATT_SJUKPENNING_MASKINELL_UNDERLAG_CREATION_FAILURE,
               "Failed to create underlag", regelRequest);
      }

      var utfall = bedomning.utfall();

      var uppgift = ImmutableUppgift.builder().from(regelRequest.uppgift())
            .utfordTs(OffsetDateTime.now())
            .build();

      var handlaggningUpdate = ImmutableHandlaggningUpdate.builder()
            .id(handlaggning.id())
            .version(handlaggning.version() + 1)
            .yrkande(yrkande)
            .processInstansId(regelRequest.processInstansId())
            .skapadTS(handlaggning.skapadTS())
            .avslutadTS(handlaggning.avslutadTS())
            .handlaggningspecifikationId(handlaggning.handlaggningspecifikationId())
            .underlag(underlag)
            .uppgift(uppgift)
            .build();

      LOGGER.info("Finished process regel for yrkande: {} with utfall: {} ({})", yrkande.id(), utfall, bedomning);

      return ImmutableRegelMaskinellSuccessResult.builder()
            .utfall(utfall)
            .handlaggningUpdate(handlaggningUpdate)
            .build();
   }

   private Result<FolkbokfordResponse> getFolkbokfordResponse(String personnummer)
   {
      try
      {
         var folkbokfordRequest = ImmutableFolkbokfordRequest.builder().personnummer(personnummer).build();
         return Result.of(folkbokfordAdapter.getFolkbokfordInfo(folkbokfordRequest));
      }
      catch (FolkbokfordException e)
      {
         return e.getErrorType() == FolkbokfordException.ErrorType.NOT_FOUND ? Result.of(null) : Result.empty();
      }
   }

   private Result<ArbetsgivareResponse> getArbetsgivareResponse(String personnummer)
   {
      try
      {
         var arbetsgivareRequest = ImmutableArbetsgivareRequest.builder().personnummer(personnummer).build();
         return Result.of(arbetsgivareAdapter.getArbetsgivareInfo(arbetsgivareRequest));
      }
      catch (ArbetsgivareException e)
      {
         return e.getErrorCode() == ArbetsgivareErrorCode.NOT_FOUND ? Result.of(null) : Result.empty();
      }
   }

   private RegelMaskinellResult createErrorResult(String felkod, String meddelande, RegelMaskinellRequest regelRequest)
   {
      var regelErrorInformation = new RegelErrorInformation();
      regelErrorInformation.setFelkod(felkod);
      regelErrorInformation.setFelmeddelande(meddelande + ". Handlaggning id: " + regelRequest.handlaggning().id()
            + ", process instans id: " + regelRequest.processInstansId()
            + ", aktivitet id: " + regelRequest.uppgift().aktivitetId());

      return ImmutableRegelMaskinellErrorResult.builder()
            .regelErrorInformation(regelErrorInformation)
            .build();
   }
}
