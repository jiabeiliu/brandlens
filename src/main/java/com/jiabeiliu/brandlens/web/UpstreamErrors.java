package com.jiabeiliu.brandlens.web;
@org.springframework.web.bind.annotation.RestControllerAdvice
public class UpstreamErrors {
 @org.springframework.web.bind.annotation.ExceptionHandler(org.springframework.web.client.RestClientException.class)
 public org.springframework.http.ResponseEntity<java.util.Map<String,String>> upstream(Exception e) {
  return org.springframework.http.ResponseEntity.status(502).body(java.util.Map.of("error","Model service unavailable; retry later"));
 }
}
