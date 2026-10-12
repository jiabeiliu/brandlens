package com.jiabeiliu.brandlens.web;
/** Valid citation IDs are not a guarantee of semantic support. */
public record SourceDto(long chunkId, String docId, double score, String excerpt, boolean cited,
                        String sourceUrl, String checkedAt) {
 public SourceDto(long id,String doc,double score,String excerpt,boolean cited) {
  this(id,doc,score,excerpt,cited,metadata(excerpt,"Source URL: (https://[^\\s]+)"),metadata(excerpt,"Checked date: (\\d{4}-\\d{2}-\\d{2})"));
 }
 private static String metadata(String text,String regex) {
  if(text==null)return null;
  var m=java.util.regex.Pattern.compile(regex).matcher(text);return m.find()?m.group(1):null;
 }
}
