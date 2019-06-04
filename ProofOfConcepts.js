//written in Rhino

var timestamp = '323B33146D42F44747881A808B81CA796996FDECFE3E1399FBB0DC89CC190743E16DBD43951A4031DC7BE2D39907CAD5515DB0CEDA26508E111CFF458C86E917BDA1CB1F75506CEB27F92E72FCDA15B7FD6E061623DFFB9C86262C82C00779EA8A7CDD0684E61DD4DD7D8C72F0AC3C42F21356BF0B3398E93E20AEF3555D2737';

function getLyricList(title,artist){
  return org.jsoup.Jsoup.connect("https://lyric.altools.com/v1/search")
                  .data('title',title)
                  .data('artist',artist)
                  .data('playtime',100)
                  .data('page',1)
                  .data('encData',timestamp)
                  .ignoreContentType(true)
                  .post()
}

function getLyricWithId(id){
  return org.jsoup.Jsoup.connect("https://lyric.altools.com/v1/info")
                  .data('info_id',id)
                  .data('encData',timestamp)
                  .ignoreContentType(true)
                  .post()
}

