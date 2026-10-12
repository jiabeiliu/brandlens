package com.jiabeiliu.brandlens.service;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.jiabeiliu.brandlens.web.*;
import java.util.List;
class InsightServiceTest {
 @Test void followupRetrievalContainsPriorTopicButNotAssistantClaims() {
  String q=InsightService.retrievalQuestion("nike","What about their pricing?",List.of(new Turn("user","Who buys running shoes?"),new Turn("assistant","Invented market share")));
  assertTrue(q.contains("running shoes")); assertTrue(q.contains("nike")); assertFalse(q.contains("Invented"));
 }
 @Test void refusesUnknownCitationAndDistinguishesUnusedEvidence() {
  var sources=List.of(new SourceDto(1,"doc",0.1,"Evidence",false),new SourceDto(2,"other",0.2,"Other",false));
  assertThrows(org.springframework.web.server.ResponseStatusException.class,()->InsightService.validateCitations("[chunk:99]",sources,null));
  var marked=InsightService.markCitations("Fact [chunk:1]",sources,null);
  assertTrue(marked.get(0).cited()); assertFalse(marked.get(1).cited());
 }
 @Test void comparisonKeepsSeparateCitationNamespaces() {
  assertThrows(org.springframework.web.server.ResponseStatusException.class,()->InsightService.validateCitations("[A:chunk:2]",List.of(new SourceDto(1,"a",0.1,"A",false)),"A"));
 }
}
