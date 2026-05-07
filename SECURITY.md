# Politique de sécurité — Read Files Tech

## Versions supportées

Seule la dernière version publiée sur GitHub Releases est activement maintenue côté sécurité.

| Version       | Supportée  |
| ------------- | ---------- |
| 2.9.x         | ✅          |
| < 2.9.0       | ❌          |

## Signaler une vulnérabilité

Si vous découvrez une vulnérabilité de sécurité dans Read Files Tech, **merci de ne PAS ouvrir d'issue publique sur GitHub**. À la place :

📧 **Envoyez un email à : contact@files-tech.com**

Indiquez dans le sujet : `[SECURITY] Read Files Tech — <description courte>`.

Merci d'inclure :

- Une description claire de la vulnérabilité
- Les étapes pour la reproduire
- L'impact potentiel
- La version affectée (visible dans l'écran « À propos » de l'app)
- Si possible, une suggestion de correctif

## Délai de réponse

- Accusé de réception : sous 7 jours
- Évaluation initiale : sous 30 jours
- Correctif : selon la criticité (critique → patch sous 30 jours, majeur → version mineure suivante, mineur → backlog)

## Divulgation responsable

Merci de ne pas divulguer publiquement la vulnérabilité avant qu'un correctif ne soit publié et qu'un délai raisonnable de mise à jour ait été laissé aux utilisateurs (typiquement 30 jours après la publication du correctif).

## Vérification de l'intégrité d'un APK

Chaque release publiée sur GitHub contient un hash SHA-256 attendu pour l'APK arm64-v8a dans les notes. Avant install, vous pouvez vérifier :

```bash
sha256sum app-arm64-v8a-release.apk
```

Le résultat doit correspondre exactement à la valeur publiée. Sinon, ne pas installer l'APK.

## Périmètre

Vulnérabilités acceptées :

- Élévation de privilèges, contournement d'autorisations
- Path traversal, zip-slip, injection via WebView ou MethodChannels
- Crash exploitable (DoS persistant)
- Lecture/écriture arbitraire hors du sandbox de l'app
- Fuite de données utilisateur

Hors périmètre :

- Bugs UX sans impact sécurité
- Vulnérabilités dans des dépendances tierces déjà reportées en amont
- Attaques nécessitant un appareil rooté/compromis
- Attaques physiques sur l'appareil déverrouillé
