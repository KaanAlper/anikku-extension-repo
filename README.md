# Anikku Extension Repo (TR)

`anime-cli` projesinde kullanılan güncel ve çalışan yöntemler temel alınarak oluşturulmuş **Anikku / Aniyomi** eklenti deposudur.

## İçerdiği Kaynaklar

| Kaynak | Paket | Yöntem |
| --- | --- | --- |
| **AnimeciX** | `eu.kanade.tachiyomi.animeextension.tr.animecix` | `animecix.tv` & `mangacix.net` JSON API + `tau-video.xyz` + TR altyazı |
| **Anizle** | `eu.kanade.tachiyomi.animeextension.tr.anizle` | `anizle.co` / `anizm.com.tr` / `anizm.pro` katalog + `puffytr` / `anizmplayer` HLS |
| **TürkAnime** | `eu.kanade.tachiyomi.animeextension.tr.turkanime` | `turkanime-gui/arsiv` & `AnimeDepo/animedepo` JSON arşivi + çoklu player extractor |

## Anikku'ya Ekleme

1. Repoyu GitHub'a pushladığınızda GitHub Actions (`.github/workflows/build_push.yml`) otomatik olarak APK'ları derleyip `repo` dalına `index.min.json` ile birlikte yayınlar.
2. Anikku içinde **Ayarlar > Gözat > Eklenti depoları > Ekle** bölümüne şu URL'yi yapıştırın:

```text
https://raw.githubusercontent.com/KaanAlper/anikku-extension-repo/repo/index.min.json
```
