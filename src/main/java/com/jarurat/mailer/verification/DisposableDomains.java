package com.jarurat.mailer.verification;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Throwaway inbox providers. Someone typed one of these to get past a download
 * gate and has never opened it since, so mail to it is a guaranteed non-open at
 * best and a spam trap at worst.
 *
 * The bundled set is a curated seed of the providers that actually show up in
 * Indian NGO signup forms, not an exhaustive blocklist. The public
 * disposable-email-domains list runs to roughly a hundred thousand entries,
 * which belongs on disk and not in a source file, so point
 * verification.disposableListPath at it and the two are merged at startup.
 */
@Component
public class DisposableDomains {

    private static final List<String> BUNDLED = Arrays.asList(
            "0-mail.com", "0clickemail.com", "0wnd.net", "10mail.org", "10minutemail.com",
            "10minutemail.net", "10minutemail.org", "1secmail.com", "1secmail.net", "1secmail.org",
            "20minutemail.com", "20minutemail.it", "2prong.com", "30minutemail.com", "33mail.com",
            "3d-painting.com", "4warding.com", "60minutemail.com", "675hosting.com", "6paq.com",
            "7tags.com", "9ox.net", "a-bc.net", "acentri.com", "afrobacon.com",
            "ajaxapp.net", "amilegit.com", "amiri.net", "anonbox.net", "anonmails.de",
            "anonymbox.com", "antichef.com", "antispam.de", "armyspy.com", "azmeil.tk",
            "beefmilk.com", "binkmail.com", "bobmail.info", "bofthew.com", "bootybay.de",
            "boun.cr", "bouncr.com", "brefmail.com", "bugmenot.com", "bumpymail.com",
            "burnermail.io", "buyusedlibrarybooks.org", "byom.de", "casualdx.com", "cek.pm",
            "centermail.com", "chogmail.com", "clixser.com", "cmail.club", "codeandscotch.com",
            "correo.blogos.net", "cosmorph.com", "courriel.fr.nf", "courrieltemporaire.com", "crapmail.org",
            "cust.in", "cuvox.de", "dacoolest.com", "dandikmail.com", "dayrep.com",
            "dbunker.com", "deadaddress.com", "deadspam.com", "despam.it", "devnullmail.com",
            "dfgh.net", "digitalsanctuary.com", "discard.email", "discardmail.com", "discardmail.de",
            "dispomail.eu", "disposableaddress.com", "disposableemailaddresses.com", "disposableinbox.com", "dispose.it",
            "dispostable.com", "dodgeit.com", "dodgit.com", "donemail.ru", "dontreg.com",
            "dontsendmespam.de", "drdrb.net", "dropmail.me", "duck2.club", "dumpandjunk.com",
            "dumpmail.de", "dumpyemail.com", "e4ward.com", "easytrashmail.com", "email60.com",
            "emailigo.de", "emailinfive.com", "emailmiser.com", "emailondeck.com", "emailsensei.com",
            "emailtemporanea.net", "emailtemporar.ro", "emailthe.net", "emailtmp.com", "emailwarden.com",
            "emailx.at.hm", "emailxfer.com", "emeil.in", "emltmp.com", "emz.net",
            "etempmail.net", "explodemail.com", "eyepaste.com", "fakeinbox.com", "fakemail.fr",
            "fakemailgenerator.com", "fakemailz.com", "fansworldwide.de", "fastacura.com", "filzmail.com",
            "fixmail.tk", "fleckens.hu", "forgetmail.com", "fr33mail.info", "frapmail.com",
            "friendlymail.co.uk", "fuckingduh.com", "garliclife.com", "gelitik.in", "get1mail.com",
            "get2mail.fr", "getairmail.com", "getnada.com", "getonemail.com", "ghosttexter.de",
            "girlsundertheinfluence.com", "gishpuppy.com", "gowikibooks.com", "grandmamail.com", "great-host.in",
            "greensloth.com", "grr.la", "guerillamail.com", "guerrillamail.biz", "guerrillamail.com",
            "guerrillamail.de", "guerrillamail.info", "guerrillamail.net", "guerrillamail.org", "guerrillamailblock.com",
            "h8s.org", "haltospam.com", "harakirimail.com", "hidemail.de", "hidzz.com",
            "hmamail.com", "hotpop.com", "hulapla.de", "ieatspam.eu", "ieatspam.info",
            "ihateyoualot.info", "iheartspam.org", "imails.info", "inbax.tk", "inbox.si",
            "inboxalias.com", "inboxbear.com", "inboxclean.com", "incognitomail.com", "incognitomail.org",
            "insorg-mail.info", "instant-mail.de", "ipoo.org", "irish2me.com", "iwi.net",
            "jetable.com", "jetable.fr.nf", "jetable.net", "jetable.org", "jnxjn.com",
            "jourrapide.com", "junk1e.com", "kasmail.com", "kaspop.com", "killmail.com",
            "killmail.net", "klassmaster.com", "klzlk.com", "koszmail.pl", "kurzepost.de",
            "letthemeatspam.com", "lhsdv.com", "lifebyfood.com", "link2mail.net", "litedrop.com",
            "lol.ovpn.to", "lookugly.com", "lortemail.dk", "lr78.com", "luxusmail.org",
            "maboard.com", "mail-filter.com", "mail-temporaire.fr", "mail.by", "mail4trash.com",
            "mail7.io", "mailbidon.com", "mailblocks.com", "mailbucket.org", "mailcat.biz",
            "mailcatch.com", "maildrop.cc", "maildx.com", "maileater.com", "mailexpire.com",
            "mailfa.tk", "mailforspam.com", "mailfreeonline.com", "mailguard.me", "mailin8r.com",
            "mailinater.com", "mailinator.com", "mailinator.net", "mailinator.org", "mailinator2.com",
            "mailincubator.com", "mailismagic.com", "mailme.lv", "mailmetrash.com", "mailmoat.com",
            "mailnesia.com", "mailnull.com", "mailorg.org", "mailpick.biz", "mailrock.biz",
            "mailsac.com", "mailscrap.com", "mailshell.com", "mailsiphon.com", "mailslapping.com",
            "mailtemp.info", "mailtome.de", "mailtothis.com", "mailtrash.net", "mailzilla.com",
            "makemetheking.com", "manybrain.com", "mbx.cc", "meltmail.com", "messagebeamer.de",
            "mierdamail.com", "migumail.com", "mintemail.com", "minuteinbox.com", "mjukglass.nu",
            "moakt.com", "mobi.web.id", "mohmal.com", "moncourrier.fr.nf", "monemail.fr.nf",
            "monmail.fr.nf", "msa.minsmail.com", "mt2009.com", "mt2014.com", "mycleaninbox.net",
            "mymail-in.net", "mypacks.net", "mytemp.email", "mytempemail.com", "mytrashmail.com",
            "nabuma.com", "neomailbox.com", "nepwk.com", "nervmich.net", "nervtmich.net",
            "netmails.net", "netzidiot.de", "neverbox.com", "nice-4u.com", "no-spam.ws",
            "nobulk.com", "noclickemail.com", "nogmailspam.info", "nomail.xl.cx", "nomail2me.com",
            "nomorespamemails.com", "nospam4.us", "nospamfor.us", "nowmymail.com", "objectmail.com",
            "obobbo.com", "odnorazovoe.ru", "one-time.email", "oneoffemail.com", "onewaymail.com",
            "online.ms", "opayq.com", "ordinaryamerican.net", "otherinbox.com", "ourklips.com",
            "outlawspam.com", "ovpn.to", "owlpic.com", "pancakemail.com", "pcusers.otherinbox.com",
            "pjjkp.com", "plexolan.de", "poczta.onet.pl", "politikerclub.de", "poofy.org",
            "pokemail.net", "privacy.net", "privatdemail.net", "proxymail.eu", "prtnx.com",
            "putthisinyourspamdatabase.com", "quickinbox.com", "rcpt.at", "reallymymail.com", "recode.me",
            "recursor.net", "regbypass.com", "rejectmail.com", "rhyta.com", "rmqkr.net",
            "royal.net", "rtrtr.com", "s0ny.net", "safe-mail.net", "safersignup.de",
            "safetymail.info", "safetypost.de", "sandelf.de", "saynotospams.com", "selfdestructingmail.com",
            "sendspamhere.com", "sharklasers.com", "shieldedmail.com", "shiftmail.com", "shitmail.me",
            "shortmail.net", "sibmail.com", "sinnlos-mail.de", "slaskpost.se", "slopsbox.com",
            "smashmail.de", "smellfear.com", "snakemail.com", "sneakemail.com", "snkmail.com",
            "sofimail.com", "sofort-mail.de", "sogetthis.com", "soodonims.com", "spam4.me",
            "spamavert.com", "spambob.net", "spambog.com", "spambog.de", "spambog.ru",
            "spambox.us", "spamcannon.com", "spamcero.com", "spamcon.org", "spamcorptastic.com",
            "spamday.com", "spamex.com", "spamfree24.com", "spamfree24.de", "spamgourmet.com",
            "spamherelots.com", "spamhereplease.com", "spamhole.com", "spamify.com", "spaminator.de",
            "spamkill.info", "spaml.com", "spammotel.com", "spamobox.com", "spamslicer.com",
            "spamspot.com", "spamthis.co.uk", "spamtroll.net", "speed.1s.fr", "spoofmail.de",
            "stuffmail.de", "supergreatmail.com", "supermailer.jp", "superrito.com", "suremail.info",
            "teewars.org", "teleworm.us", "temp-mail.org", "temp-mail.ru", "tempail.com",
            "tempe-mail.com", "tempemail.biz", "tempemail.com", "tempemail.net", "tempinbox.co.uk",
            "tempinbox.com", "tempmail.eu", "tempmail.it", "tempmail2.com", "tempmaildemo.com",
            "tempmailer.com", "tempmailo.com", "tempomail.fr", "temporarily.de", "temporarioemail.com.br",
            "temporaryemail.net", "temporaryforwarding.com", "temporaryinbox.com", "temporarymailaddress.com", "tempr.email",
            "tempsky.com", "tempthe.net", "thanksnospam.info", "thankyou2010.com", "thisisnotmyrealemail.com",
            "throwam.com", "throwawayemailaddress.com", "throwawaymail.com", "tilien.com", "tmail.ws",
            "tmailinator.com", "tmpeml.com", "tmpmail.net", "tmpmail.org", "toomail.biz",
            "topranklist.de", "tradermail.info", "trash-amil.com", "trash-mail.at", "trash-mail.com",
            "trash-mail.de", "trash2009.com", "trashdevil.com", "trashemail.de", "trashmail.at",
            "trashmail.com", "trashmail.de", "trashmail.me", "trashmail.net", "trashmail.org",
            "trashmailer.com", "trashymail.com", "trbvm.com", "trialmail.de", "trillianpro.com",
            "twinmail.de", "tyldd.com", "uggsrock.com", "upliftnow.com", "uplipht.com",
            "uroid.com", "venompen.com", "veryrealemail.com", "viditag.com", "viralplays.com",
            "vpn.st", "vsimcard.com", "vubby.com", "wasteland.rfc822.org", "webemail.me",
            "weg-werf-email.de", "wegwerf-emails.de", "wegwerfadresse.de", "wegwerfemail.com", "wegwerfemail.de",
            "wegwerfmail.de", "wegwerfmail.info", "wegwerfmail.net", "wegwerfmail.org", "wetrainbayarea.com",
            "wh4f.org", "whatiaas.com", "whatpaas.com", "whyspam.me", "willhackforfood.biz",
            "willselfdestruct.com", "winemaven.info", "wronghead.com", "wuzup.net", "wuzupmail.net",
            "wwwnew.eu", "xagloo.com", "xemaps.com", "xents.com", "xmaily.com",
            "xoxy.net", "yapped.net", "yeah.net", "yep.it", "yogamaven.com",
            "yopmail.com", "yopmail.fr", "yopmail.net", "yourdomain.com", "ypmail.webarnak.fr.eu.org",
            "yuurok.com", "z1p.biz", "za.com", "zehnminuten.de", "zehnminutenmail.de",
            "zippymail.info", "zoaxe.com", "zoemail.org", "zomg.info"
    );

    private final Set<String> domains;

    public DisposableDomains(@Value("${verification.disposableListPath:}") String overridePath) {
        Set<String> merged = new HashSet<>(BUNDLED);
        if (overridePath != null && !overridePath.isBlank()) {
            try {
                for (String line : Files.readAllLines(Path.of(overridePath), StandardCharsets.UTF_8)) {
                    String clean = line.trim().toLowerCase();
                    if (clean.isEmpty() || clean.startsWith("#")) continue;
                    merged.add(clean);
                }
                System.out.println("Disposable domain list: " + BUNDLED.size() + " bundled, "
                        + merged.size() + " after merging " + overridePath);
            } catch (Exception e) {
                // Not fatal. Losing the big list costs recall, not correctness.
                System.err.println("Could not read " + overridePath
                        + ", using the bundled list only: " + e.getMessage());
            }
        }
        this.domains = Collections.unmodifiableSet(merged);
    }

    public boolean contains(String domain) {
        if (domain == null || domain.isBlank()) return false;
        return domains.contains(domain.trim().toLowerCase());
    }

    public int size() { return domains.size(); }
}
