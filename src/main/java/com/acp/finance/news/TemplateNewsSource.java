package com.acp.finance.news;

import com.acp.finance.model.NewsArticle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

@Component
@ConditionalOnProperty(
    name = "NEWS_SOURCE",
    havingValue = "template",
    matchIfMissing = true)
public class TemplateNewsSource implements NewsSource {

    private static final Logger log = LoggerFactory.getLogger(TemplateNewsSource.class);

    private final Random random = new Random();

    @Value("${SPY_BIAS:0.65}")
    private double spyBias;

    @Value("${QQQ_BIAS:0.6}")
    private double qqqBias;

    @Value("${TLT_BIAS:0.45}")
    private double tltBias;

    @Value("${GLD_BIAS:0.55}")
    private double gldBias;

    @Value("${USO_BIAS:0.4}")
    private double usoBias;

    // Per-instrument bullish headline pools
    private static final Map<String, String[]> BULLISH_TEMPLATES = Map.of(
        "SPY", new String[]{
            "US stocks rally after softer-than-expected inflation data",
            "Wall Street surges as strong jobs report fuels growth optimism",
            "S&P 500 hits record high on upbeat consumer sentiment",
            "US equities climb as Fed signals patient approach to rate hikes",
            "Broad market rally lifts S&P 500 on easing recession fears",
            "US stocks advance as corporate earnings beat expectations broadly",
            "Risk appetite returns as US economic data surprises to the upside"
        },
        "QQQ", new String[]{
            "Big tech leads Nasdaq higher on AI infrastructure optimism",
            "Growth stocks surge as bond yields retreat from recent highs",
            "Nasdaq 100 rallies after strong earnings from mega-cap tech",
            "Tech stocks climb as cloud spending outlook improves",
            "Nasdaq outperforms as AI demand drives upside revenue surprises",
            "QQQ jumps as interest rate cut expectations lift growth valuations",
            "Big tech shares rise sharply after robust forward guidance"
        },
        "TLT", new String[]{
            "Treasury bonds rally as rate-cut expectations build",
            "Long-duration bonds surge after dovish Fed commentary",
            "TLT rises as softer CPI data boosts bond market confidence",
            "US Treasuries climb on safe-haven demand amid global uncertainty",
            "10-year yield falls sharply as growth fears prompt bond buying",
            "Bond market rallies after Fed chair signals policy pivot ahead",
            "Long-term Treasuries gain as inflation expectations moderate"
        },
        "GLD", new String[]{
            "Gold rises on safe-haven demand as geopolitical tensions escalate",
            "GLD advances as dollar weakens following soft US economic data",
            "Gold prices climb on inflation hedge demand from institutional buyers",
            "Precious metals rally as real yields decline globally",
            "Gold hits multi-week high amid central bank buying reports",
            "GLD gains as investors rotate into defensive assets",
            "Gold rebounds as recession concerns fuel safe-haven flows"
        },
        "USO", new String[]{
            "Oil jumps on unexpected supply disruption fears",
            "Crude rises after OPEC signals further production cuts",
            "USO advances as strong global demand outlook lifts energy prices",
            "Oil prices surge following larger-than-expected inventory draw",
            "Crude climbs as Middle East tensions raise supply risk premium",
            "Energy markets rally as US driving season demand beats forecasts",
            "WTI crude gains after Saudi Arabia extends voluntary output cuts"
        }
    );

    // Per-instrument bearish headline pools
    private static final Map<String, String[]> BEARISH_TEMPLATES = Map.of(
        "SPY", new String[]{
            "Wall Street falls as recession fears deepen on weak data",
            "US stocks slide after hotter-than-expected inflation reading",
            "S&P 500 drops as Fed hawkishness unnerves equity investors",
            "US equities sell off on mounting credit market stress",
            "Broad market declines as consumer confidence hits multi-month low",
            "S&P 500 retreats as earnings season disappoints broadly",
            "US stocks fall sharply amid rising concerns over debt ceiling talks"
        },
        "QQQ", new String[]{
            "Growth stocks slide as Treasury yields climb sharply",
            "Nasdaq 100 falls after disappointing tech earnings reports",
            "Big tech leads market lower on valuation concerns",
            "QQQ drops as investors rotate out of high-multiple growth names",
            "Tech sector pressured as AI spending scrutiny intensifies",
            "Nasdaq slips after weak guidance from key semiconductor firms",
            "Growth stocks underperform as rate-cut bets are scaled back"
        },
        "TLT", new String[]{
            "Long-duration bonds fall after hawkish Fed meeting minutes",
            "Treasury yields spike as inflation data exceeds expectations",
            "TLT slides as strong jobs data reduces rate-cut probability",
            "Bond market sells off following upside surprise in core PCE",
            "Long-term Treasuries decline as fiscal deficit concerns mount",
            "TLT drops after Fed official rules out near-term policy easing",
            "US bond prices fall as supply concerns weigh on the market"
        },
        "GLD", new String[]{
            "Gold slips as dollar strengthens on hawkish Fed outlook",
            "GLD falls as rising real yields reduce precious metals appeal",
            "Gold retreats as risk appetite improves across global markets",
            "Precious metals under pressure after strong US economic data",
            "Gold declines as dollar index reaches fresh multi-month highs",
            "GLD drops on profit-taking after recent safe-haven surge",
            "Gold weakens as inflation expectations fall back toward target"
        },
        "USO", new String[]{
            "Crude falls on weak global demand outlook and rising inventories",
            "Oil prices drop as US production reaches record highs",
            "USO slides after disappointing Chinese manufacturing data",
            "Crude oil falls sharply on OPEC compliance concerns",
            "Energy markets retreat as recession fears dampen demand outlook",
            "Oil declines after surprise build in US crude stockpiles",
            "WTI crude drops as demand forecasts are revised lower"
        }
    );

    // Per-instrument neutral headline pools
    private static final Map<String, String[]> NEUTRAL_TEMPLATES = Map.of(
        "SPY", new String[]{
            "US equities trade in a narrow range ahead of key economic data",
            "S&P 500 holds steady as investors await Fed policy statement",
            "Wall Street mixed as earnings season enters final stretch",
            "US stocks little changed amid light holiday trading volume"
        },
        "QQQ", new String[]{
            "Nasdaq 100 consolidates near recent highs ahead of tech earnings",
            "Growth stocks flat as market awaits clarity on rate path",
            "Big tech trades sideways in subdued pre-earnings session"
        },
        "TLT", new String[]{
            "Treasury bonds range-bound as market awaits inflation print",
            "Long-duration bonds hold steady amid balanced macro signals",
            "US bond yields little changed in cautious trading session"
        },
        "GLD", new String[]{
            "Gold trades flat as dollar and yields offset safe-haven demand",
            "Precious metals consolidate after recent two-week rally",
            "GLD holds near support ahead of key US inflation data"
        },
        "USO", new String[]{
            "Crude oil holds recent gains as market awaits OPEC meeting outcome",
            "Oil prices little changed in thin energy market trading",
            "USO consolidates as traders weigh supply and demand signals"
        }
    );

    @Override
    public List<NewsArticle> getLatestHeadlines(String ticker, int count) {
        double bias = getBias(ticker);
        List<NewsArticle> articles = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            double roll = random.nextDouble();
            String title;
            if (roll < 0.1) {
                title = pickTemplate(NEUTRAL_TEMPLATES, ticker);
            } else if (roll < bias) {
                title = pickTemplate(BULLISH_TEMPLATES, ticker);
            } else {
                title = pickTemplate(BEARISH_TEMPLATES, ticker);
            }

            NewsArticle article = new NewsArticle();
            article.setTicker(ticker);
            article.setTitle(title);
            article.setDescription("");
            article.setPublishedAt(Instant.now().toString());
            article.setSource("TemplateNews");
            articles.add(article);

            log.info("[TemplateNews] {} -> {}", ticker, title);
        }

        return articles;
    }

    private double getBias(String ticker) {
        return switch (ticker) {
            case "SPY" -> spyBias;
            case "QQQ" -> qqqBias;
            case "TLT" -> tltBias;
            case "GLD" -> gldBias;
            case "USO" -> usoBias;
            default    -> 0.5;
        };
    }

    private String pickTemplate(Map<String, String[]> pool, String ticker) {
        String[] templates = pool.getOrDefault(ticker, pool.values().iterator().next());
        return templates[random.nextInt(templates.length)];
    }
}
