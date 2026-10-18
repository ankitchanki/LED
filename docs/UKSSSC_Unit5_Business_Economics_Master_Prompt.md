# UKSSSC UNIT V — BUSINESS ECONOMICS
## MASTER DEVELOPMENT, RESEARCH, AUDIT & HTML INTEGRATION INSTRUCTIONS (v2.1 — aligned to official syllabus)
### 31 Syllabus Topics | 13 Integrated Chapters | One Offline Master HTML

---

## 0. HOW TO READ THIS PROMPT

- Rules marked **(Non-negotiable)** always win.
- If two rules conflict, follow this order: **Accuracy → Syllabus completeness → Approved UI → New features**.
- If something is genuinely unclear and changes the result (e.g. the intended meaning of a syllabus term), ask me **once**, briefly. Otherwise use the defaults given here and proceed.
- Never ask me to restate rules already written here.

---

## 1. YOUR ROLE & OBJECTIVE

Act simultaneously as:

- Expert Economics educator (concept-first, no filler)
- NCERT and official-source researcher
- Academic fact-checker
- Competitive-exam MCQ setter (UKSSSC level)
- Mathematical and SVG accuracy auditor
- HTML / CSS / JavaScript developer
- Quality-assurance reviewer

**Objective:** Produce academically complete, logically connected, exam-oriented Business Economics notes covering all 31 syllabus topics, inside one cumulative offline HTML Master, so that a student can understand every topic completely, **without confusion and without needing another book**.

Statistics (the other part of Unit V) is already complete. **Do not recreate, edit or include Statistics.**

---

## 2. COMPLETE SYLLABUS (Non-negotiable)

### 2.0 Official syllabus text (the final authority)

> **Business Economics:** Definition, Nature, Scope and Methodology of Economics, Micro and Macro Economics. Measurements of Utility, Law of Diminishing Marginal Utility, Law of Equi-Marginal Utility, Demand Schedule and Curves, Elasticity of Demand, Methods of Measurement of Elasticity of Demand and Elasticity of Supply, Consumer's Surplus, Consumer's Equilibrium- Indifference Curve Analysis. Production function and returns to factor, Concepts of Cost of Production, SAC, LAC Curves, Theory of Exchange, Forms of markets and market equilibrium under perfect competition, Concept of Economic Growth and Development. Conceptual overview of the National and State Income. An overview of Economy of Uttarakhand.

The 31-topic list below is a breakdown of this text. **If the breakdown and the official text ever differ, the official text wins.** Every phrase of the official text must map to at least one topic (see §2.5).

All 31 original topics must remain individually identifiable, numbered and directly navigable. Never delete, skip, renumber or silently replace a topic.

### M1 — Economics Foundations
| Topic | Subject |
|---|---|
| T1 | Definition of Economics |
| T2 | Nature of Economics |
| T3 | Scope of Economics |
| T4 | Methodology of Economics |
| T5 | Microeconomics |
| T6 | Macroeconomics |

### M2 — Utility, Demand and Consumer Behaviour
| Topic | Subject |
|---|---|
| T7 | Measurements of Utility |
| T8 | Law of Diminishing Marginal Utility |
| T9 | Law of Equi-Marginal Utility |
| T10 | Demand Schedule |
| T11 | Demand Curves (including the Law of Demand) |
| T12 | Elasticity of Demand |
| T13 | Methods of Measurement of Elasticity of Demand |
| T14 | Elasticity of Supply |
| T15 | Methods of Measurement of Elasticity of Supply |
| T16 | Consumer's Surplus |
| T17 | Indifference Curve Analysis — IC, MRS, Properties, Budget Line |
| T18 | Consumer's Equilibrium through Indifference Curve Analysis |

### M3 — Production, Costs and Markets
| Topic | Subject |
|---|---|
| T19 | Production Function |
| T20 | Returns to Factor |
| T21 | Concepts of Cost of Production |
| T22 | Short-Run Average Cost Curve |
| T23 | Long-Run Average Cost Curve |
| T24 | Theory of Exchange |
| T25 | Forms of Market |
| T26 | Market Equilibrium under Perfect Competition |

### M4 — Growth, Development and Uttarakhand Economy
| Topic | Subject |
|---|---|
| T27 | Concept of Economic Growth |
| T28 | Concept of Economic Development |
| T29 | Conceptual Overview of National Income |
| T30 | Concept of State Income |
| T31 | An Overview of the Economy of Uttarakhand |

### 2.5 Official syllabus → topic mapping and changes from the earlier list

| Official phrase | Topic(s) | Note |
|---|---|---|
| Definition, Nature, Scope and Methodology of Economics | T1–T4 | — |
| Micro and Macro Economics | T5–T6 | — |
| Measurements of Utility | T7 | Cardinal and ordinal measurement |
| Law of Diminishing Marginal Utility | T8 | — |
| Law of Equi-Marginal Utility | T9 | Now also **owns the cardinal consumer equilibrium** (MUₓ/Pₓ = MUᵧ/Pᵧ = MUₘ) |
| Demand Schedule and Curves | T10–T11 | Law of Demand is taught inside T11 because a demand curve cannot be explained without it |
| Elasticity of Demand | T12 | — |
| Methods of Measurement of Elasticity of Demand and Elasticity of Supply | T13, T14, T15 | Read as: methods for **both** demand and supply elasticity. Elasticity of supply needs its meaning and types (T14) before its methods (T15), so both stay |
| Consumer's Surplus | T16 | — |
| Consumer's Equilibrium – Indifference Curve Analysis | T17–T18 | **Changed.** Official text ties equilibrium to the IC (ordinal) approach. T17 = IC tools; T18 = equilibrium (MRS = Pₓ/Pᵧ, tangency with budget line), with a short comparison to the cardinal approach (linked to T9) |
| Production function and returns to factor | T19–T20 | Returns to scale added as a short Prerequisite block (needed for LAC in T23) |
| Concepts of Cost of Production, SAC, LAC Curves | T21–T23 | — |
| Theory of Exchange | T24 | **Renamed.** See §6.4 for scope |
| Forms of markets | T25 | — |
| Market equilibrium under perfect competition | T26 | **Broadened.** Covers industry (market) equilibrium by demand and supply, firm equilibrium (short and long run), and effects of demand/supply shifts — not only firm price-output |
| Concept of Economic Growth and Development | T27–T28 | — |
| Conceptual overview of the National and State Income | T29–T30 | Keep it **conceptual**: meanings, aggregates, methods, difficulties. No heavy numerical accounting beyond simple examples |
| An overview of Economy of Uttarakhand | T31 | Keep it an **overview** with key, dated facts — not a full state report |

**Migration rule for the changed topics:** if the existing Master has content under the old T17 "Consumer's Equilibrium — Cardinal Approach", move that content to T9 (cardinal equilibrium) or into the comparison block of T18. Do not delete it. Move its MCQs with it and update their concept targets.

---

## 3. THE 13 INTEGRATED CHAPTERS

| Chapter | Topics | Integrated Chapter |
|---|---|---|
| C1 | T1–T4 | Foundations of Economics |
| C2 | T5–T6 | Microeconomics and Macroeconomics |
| C3 | T7–T9 | Utility Analysis |
| C4 | T10–T11 | Theory of Demand |
| C5 | T12–T13 | Elasticity of Demand and Measurement |
| C6 | T14–T15 | Elasticity of Supply and Measurement |
| C7 | T16–T18 | Consumer Surplus, Indifference Curves and Consumer Equilibrium |
| C8 | T19–T20 | Production Function and Returns |
| C9 | T21–T23 | Theory of Cost — Concepts, SAC and LAC |
| C10 | T24–T26 | Theory of Exchange, Forms of Market and Equilibrium under Perfect Competition |
| C11 | T27–T28 | Economic Growth and Development |
| C12 | T29–T30 | National and State Income |
| C13 | T31 | Economy of Uttarakhand |

**Chapters are reading groups. Topics are syllabus units. Never confuse chapter numbers with topic numbers.**

Each chapter must:
1. Contain all its assigned topics in a continuous, logical learning sequence.
2. Remove overlap between neighbouring topics (see §6 Concept Ownership).
3. Preserve the complete unique content, MCQs and diagrams of every topic.
4. Keep a direct navigation anchor for every topic (e.g. `id="t8"`).

Example: C3 reads as one chapter, but clicking Topic 8 in navigation goes directly to the Diminishing Marginal Utility section.

---

## 4. EXISTING MASTER & UPLOADED FILES (Non-negotiable)

Before changing anything, inspect the latest Master HTML and any uploaded source files/notes.

Run three separate checks for every topic:
1. Is source material present in an uploaded file?
2. Is it actually embedded in the current Master?
3. Is it reachable through navigation and rendering correctly?

**Presence is not completeness.** A section heading or five MCQs does not mean a topic is finished.

Preservation rules:
- Do not overwrite detailed content with a shorter summary.
- Do not remove verified SVGs, unique numerical examples or explanations.
- Do not delete a question bank without a real question-by-question audit.
- Do not silently replace approved work.
- Keep working HTML/CSS/JS features unless they are objectively broken.
- If old source material and the Master disagree, state the difference and keep whichever is more complete and accurate.

---

## 5. CONTENT STYLE — CONCEPTS ONLY, NO FILLER (Non-negotiable)

- **No filler.** No introductory chit-chat, motivational lines, storytelling, promotional headings, "let's learn" sentences, decorative statistics or artificial recap strips.
- **One idea per block.** Each block teaches exactly one concept.
- **Default block pattern:** Meaning/Definition → Diagram (if it helps) → Key points or formula → Example (only if it adds understanding).
- **Prerequisites first.** Never use a term before it has been explained (or linked to where it is explained).
- **Every sentence must teach something.** If a sentence can be deleted without the student losing anything, delete it.

### Language
- Notes: **Roman Hinglish**, with standard academic terms in English.
- MCQ questions and options: **English**.
- MCQ explanations: **Roman Hinglish**.
- Maths: conventional notation.

### Conceptual progression (use only what the topic actually needs)
1. Meaning  2. Definitions (with economist name where standard)  3. Why it matters  4. Background/evolution  5. Characteristics  6. Assumptions  7. Mechanism/theory  8. Types  9. Mathematical form  10. SVG graph  11. Worked numerical  12. Exceptions/limitations/applications  13. Key comparisons  14. Exam traps and interlinks

---

## 6. GAP DETECTION, CONCEPT OWNERSHIP & INTERLINKING

### 6.1 Self-assessed gap detection (mandatory for every topic)
Before writing, check what a student must already know to understand the topic, and what UKSSSC-level questions commonly test on it.

- If a needed concept is **not in the 31-topic syllabus**, add it as a short, clearly labelled **"Prerequisite"** block in the topic where it is first needed (concise; no full chapter).
  Examples to check: opportunity cost, PPC/PPF, ceteris paribus, slope of a curve, total/average/marginal relationship, percentage change, normal/inferior/Giffen goods, substitutes/complements, revenue concepts (TR, AR, MR), real vs nominal, GDP deflator, per-capita income, HDI.
- If a concept is **in the syllabus but missing from the content**, list it as **Missing** in the Coverage Ledger (§8) and add it.
- Report every gap filled in the audit report.

### 6.2 Concept Ownership Map (avoid duplication)
Each shared concept is explained **in full exactly once** (in its "owner" topic). Everywhere else, give a one-line reminder plus a link.

| Shared concept | Owner topic | Other topics use a reminder + link |
|---|---|---|
| Cardinal vs ordinal utility | T7 | T17, T18 |
| Indifference curve, MRS, budget line | T17 | T18 |
| TU / MU relationship | T7–T8 | T9, T16, T17 |
| Equi-marginal principle and cardinal equilibrium (MU/P = MUₘ) | T9 | T18 (comparison only) |
| Demand schedule & curve | T10–T11 | T12, T16, T26 |
| Elasticity formulas (point, arc, percentage, total outlay) | T13 | T15 (supply versions only) |
| Short-run vs long-run | T19–T20 | T22, T23, T26 |
| TC / AC / MC relationships | T21–T22 | T23, T26 |
| AR / MR (revenue concepts) | T25 | T26 |
| Demand–supply equilibrium price | T26 | T24 (if the chosen meaning of Exchange uses it) |
| Growth vs development | T27–T28 | T29, T31 |
| GDP / GNP / NDP / NNP, methods of measurement | T29 | T30, T31 |

Adjust the map if the audit shows a better owner, and state the change.

### 6.3 Interlinking
Every topic shows two small link lines at its start:
- **Needs:** links to prerequisite topics/blocks
- **Used in:** links to later topics that build on it

Required chapter connections:

| Connection | Relationship |
|---|---|
| C1 → C2 | Foundations → Micro and Macro |
| C2 → C3 | Micro → Utility |
| C3 → C4 | Utility → Demand |
| C4 → C5 | Demand → Elasticity |
| C5 → C6 | Demand and Supply responsiveness |
| C3 → C7 | Utility → Consumer Surplus and Equilibrium |
| C4–C6 → C10 | Demand/Supply → Market price |
| C8 → C9 | Production → Cost |
| C9 → C10 | Cost → Competitive firm equilibrium |
| C11 → C12 | Growth/Development → Income indicators |
| C12 → C13 | National/State income → Uttarakhand economy |

All internal links must work across chapters and be tested.

### 6.4 Ambiguous syllabus terms
- **T24 "Theory of Exchange":** this phrase has different meanings in different textbooks:
  1. Meaning of exchange, barter vs money exchange, conditions, merits and limitations of barter.
  2. Exchange as the basis of price/value determination (how markets set prices through demand and supply).
  3. Gains from exchange between two consumers (Edgeworth box, contract curve) — mostly at higher levels.

  First check the B.Com/BA Business Economics syllabi of Uttarakhand universities (Kumaun University, HNB Garhwal University, Sri Dev Suman Uttarakhand University), because UKSSSC syllabi usually follow them. Default if that is unclear: teach (1) and (2) fully, and (3) as a short concept-level block with its own MCQs. State which interpretation was used and why.
- **T7 "Measurements of Utility":** must cover both cardinal (utils, Marshall) and ordinal (ranking, Hicks–Allen) measurement.
- **T26 "market equilibrium":** include Marshall's time element (market period, short period, long period) if the chosen sources treat it under perfect competition.

---

## 7. RESEARCH & SOURCES

### Source priority
1. NCERT Class XI — Introductory Microeconomics
2. NCERT Class XII — Introductory Macroeconomics
3. NIOS and other official educational resources
4. RBI, MoSPI, NSO and other Government of India sources
5. Government of Uttarakhand: Economic Survey, Budget documents, Directorate of Economics and Statistics
6. Other credible academic references only where necessary

### Verify
Definitions, economists and their theories, assumptions, laws, derivations, graph shapes, exceptions, numericals, conceptual distinctions and current data.

### Data rules
- Every current or Uttarakhand figure must state its **financial year / reference date and source**.
- Use the **latest available** official edition, and say which edition was used.
- **Never invent statistics.** If a figure cannot be verified, leave it out and list it under "Needs Independent Verification".
- Do not mark a whole topic verified because one source supports its general theme.
- If you have no web access in the current session, say so and label affected facts **"Not verified this session"**.

---

## 8. WORKFLOW FOR EVERY COMMAND

Run these phases automatically.

### Phase 1 — Audit existing content
For the selected topic/chapter:
1. Open its current HTML content, neighbouring topics, tables, SVGs, numericals.
2. Count existing MCQs.
3. Find weak explanations, missing concepts, duplicates, wrong definitions/formulas, broken anchors.
4. Compare its visual design with Topic 1.

Build a **Concept Coverage Ledger**, giving every syllabus concept a status:
**Covered & Verified / Covered but Needs Improvement / Missing / Needs Independent Verification**

### Phase 2 — Research (§7)

### Phase 3 — Gap detection & ownership (§6)

### Phase 4 — Teaching outline
Write the concept order first (prerequisites → core → applications → traps). Then write the content.

### Phase 5 — Build
Content blocks (§5), tables (§9), formula SVGs and diagrams (§10), MCQs (§11).

### Phase 6 — Integrate into the Master (§15)

### Phase 7 — QA (§16) and report (§17)

**Do not start rebuilding blindly.**

### Output-size rule
A complete chapter may be too long for one reply.
- Never cut a topic in the middle.
- Split only at topic boundaries, say clearly what is done and what remains, and continue on "Continue".
- When the Master is large, deliver changes as clearly marked **replace-this-section blocks** (by element `id`) or as the full file, whichever is reliable in the current environment, and say which one you used.

---

## 9. TABLES — ONLY WHEN THEY EARN THEIR PLACE

**Use tables for:** numerical schedules (TU/MU/AU, demand/supply, production, cost, revenue), genuine multi-factor comparisons, elasticity calculations, market-form comparisons, income calculations.

**Use cards/short paragraphs for:** definitions, short assumptions, simple characteristics, classifications, examples already explained, ideas an SVG explains better.

Before adding or keeping a table, ask:
1. Is the information unique?
2. Is it mathematically necessary?
3. Does it repeat a nearby paragraph or a diagram?
4. Would a card or short example be clearer?
5. Does it help revision or numerical work?

Never remove essential numerical tables just to reduce the table count.

---

## 10. FORMULAS & DIAGRAMS — ACCURATE OFFLINE SVG (Non-negotiable)

### Formulas
- No raw LaTeX, no external MathJax/KaTeX, no CDN dependency. Everything must work offline.
- Pipeline: verify the expression → typeset → inline vector SVG → visual check → browser check.
- Prefer SVG `<text>`/`<tspan>` (real text, so it stays sharp and searchable) over outlined paths.
- Use `fill="currentColor"` or CSS variables so formulas stay readable in dark mode.
- Every formula SVG has `role="img"` and an `aria-label` that reads the formula in words.

Formula checklist: numerator/denominator, sub/superscripts, Δ and Greek letters, signs (including the negative sign in price elasticity, and whether the absolute value is used), brackets, units, valid assumptions, no clipping, correct aspect ratio, readable at 320px.

Key formula areas: TU/MU/AU; MU-per-rupee equilibrium (MUₓ/Pₓ = MUᵧ/Pᵧ = MUₘ); demand/supply elasticity (percentage, point, arc/midpoint, total outlay, geometric); MRS and budget line; TP/AP/MP; TC/TFC/TVC/AC/AVC/AFC/MC; TR/AR/MR, profit; national/state income identities.

### Conceptual diagrams
Diagrams must explain mechanisms, not decorate.
Required where relevant: TU–MU curves; consumer equilibrium; indifference curves + budget line; movement vs shift of demand/supply; elasticity cases (perfectly elastic → perfectly inelastic, unit elastic); TP–AP–MP with three stages; SAC/AVC/AFC/MC with MC cutting AVC and SAC at their minimums; LAC as envelope of SACs; AR/MR under different market forms; competitive firm short-run and long-run equilibrium; circular flow; income measurement.

For each diagram, check: axes and units, labels, curve shapes, intersection points, consistency with the numerical table, and assumptions.

Each topic should also start with a small **topic-map SVG** showing where it sits in its chapter, if this fits the approved UI.

Do not replace correct existing SVGs without a reason.

---

## 11. MCQs — ORIGINAL, VARIED, VERIFIED

### Format (every question)
1. English question
2. Four meaningful English options
3. One unambiguous correct answer
4. Roman Hinglish explanation, explaining **why the correct option is right and why each wrong option is wrong**
5. Concept target (`data-concept="<section-id>"`)
6. Working **Show Concept** link
7. Inline **↩ Back to Question**

### Variety
Definitions; conceptual understanding; theory/economist identification; assumptions and exceptions; application; graph interpretation; numericals; statement-based (Statement I/II, "which is/are correct"); match-the-following; assertion–reason; comparisons; multi-step reasoning; common exam traps.

### Count policy
No artificial counts. Guideline: roughly **40–80 per standalone topic**, adjusted for breadth, existing coverage and quality. When merging topics, **deduplicate first**, then fill uncovered objectives. Every concept in the Coverage Ledger must be tested at least once.

### Mandatory audit (old and new questions)
Syllabus relevance; independent answer check; recalculate numericals; check all distractors; explanation accuracy; exact and semantic duplicates (within and across chapters); Show Concept target; Back to Question; scoring and reset.

Ledger status per question: **Verified / Corrected / Needs Review**.

- Never label original questions as PYQs. Mark a question as a PYQ only if the exam name and year can be cited.
- Never claim questions were audited unless the audit actually happened.

---

## 12. UI — FOLLOW THE APPROVED TOPIC 1 DESIGN (Non-negotiable)

The latest approved Master and Topic 1 are the design reference. Preserve: page width, fonts and hierarchy, colour palette, cards, borders, spacing, diagram style, responsive layout, MCQ style, dark mode, existing navigation, compact floating controls.

Do **not** add: duplicate top navigation, extra contents strips, recap buttons, promotional banners, unnecessary notes sections, repeated topic titles, decorative counters.

Technical defaults:
- Single self-contained HTML file; no external fonts, scripts or images (offline first).
- Colours as CSS variables, with a dark-mode set.
- No horizontal page scroll at 320px.
- Print stylesheet: hides controls, expands all content, prints cleanly to PDF.

---

## 13. RIGHT-SIDE NAVIGATION (Non-negotiable)

All navigation stays on the **right side** as a compact vertical stack.

Topic numbers of the **active module** appear **directly below that module label and above the next module label**. Never beside the labels, and never replaced by chapter numbers.

| Active module | Stack |
|---|---|
| M1 | M1, 1, 2, 3, 4, 5, 6, M2, M3, M4 |
| M2 | M1, M2, 7–18, M3, M4 |
| M3 | M1, M2, M3, 19–26, M4 |
| M4 | M1, M2, M3, M4, 27–31 |

Clicking a topic number must:
1. Find its integrated chapter.
2. Activate that chapter.
3. Scroll to the exact topic section.
4. Keep MCQ state and navigation state intact.

Highlight the topic currently being read (scroll-spy).

### Always-visible controls below the navigation
**Q** (open current chapter's MCQs) · **Theme** (light/dark) · **↑** · **↓**

- Always visible: never hidden on scroll, inactivity or without hover.
- No large opaque panel behind them; compact individual buttons with good contrast.
- M1–M4 also always reachable.

### Mobile
Keep the stack compact. An expanded topic list may scroll within a limited height. Never cover large parts of the notes. No page-level horizontal overflow. Q, Theme and arrows always reachable.

---

## 14. INTERACTIVE FEATURES

### 14.1 Required
**Show Concept → ↩ Back to Question**
1. Scroll to the exact supporting block.
2. Briefly highlight it.
3. Show a small inline ↩ Back to Question next to the block (never a large fixed button).
4. Return to the same question, with the selected answer and score kept.
5. Must work across merged topics and across chapters, on desktop and mobile.

**Instant MCQ feedback:** right/wrong colour, the correct answer revealed, the explanation shown. Score shown with a Reset button.

### 14.2 Additional learning features
Add these **only in ways that do not change the approved UI or the right-side stack**, e.g. inside the existing Q/MCQ panel or chapter header. If a feature cannot fit without breaking §12/§13, skip it and say so.

- **Weak-concept summary:** after a quiz, list the concepts answered wrongly, each with a Show Concept link.
- **Retry wrong questions only.**
- **Bookmark ⭐** on concept blocks and questions, with a "Bookmarked" filter inside the Q panel.
- **Progress:** mark a topic as done; show done/not-done on topic numbers in the right stack (e.g. a small dot, no counters).
- **Search** across all notes (inside the Q panel or a small search field in the chapter header).
- **Revision mode:** a toggle that collapses explanations and shows only definitions, formulas, key diagrams and exam traps. This replaces any separate "recap" section.
- **Flashcards:** tap-to-flip definitions and formulas, generated from the same content (no duplicate text in the source).
- **Tappable diagram labels** where a diagram has many parts.

### 14.3 Saved state
Save score, answers, bookmarks, progress and theme in `localStorage`. Wrap every read/write in `try/catch`, and make sure the page works normally if storage is unavailable.

---

## 15. SPECIAL ACADEMIC PRIORITIES

### Priority A — Topics 21–26
Verify complete coverage of: explicit/implicit cost; opportunity cost; accounting vs economic cost; fixed/variable cost; TC/AC/MC; AFC, AVC, ATC relationships; MC cutting AVC and ATC at their minimums; reasons for the U-shaped SAC; LAC as envelope (planning curve); economies and diseconomies of scale (internal and external); Theory of Exchange (scope per §6.4); market classification bases; characteristics of perfect competition, monopoly, monopolistic competition, oligopoly (and duopoly where relevant); price taker vs price maker; AR = MR = P under perfect competition; industry equilibrium (demand = supply) and effects of shifts; time element; TR–TC and MR–MC approaches; firm vs industry; short-run (profit, normal profit, loss, shutdown point) and long-run equilibrium; diagrams and numericals for each.

### Priority B — Topic 31: Economy of Uttarakhand
Use official Uttarakhand sources (§7). Cover where relevant: formation and geography of the economy; GSDP, per-capita income and sectoral shares; agriculture and horticulture (hill vs plains); industry (industrial areas, MSMEs, policies); services; tourism (religious, adventure, eco); forests and hydropower; employment, livelihood and migration; infrastructure; regional disparity (hill vs plain districts); key state schemes; growth and development challenges. Each figure must have its year and source.

### Priority C — All remaining topics
Audit for missing theory, weak numericals, misleading diagrams, duplicated tables, repeated questions, wrong answer keys, missing explanations and broken concept links.

---

## 16. CUMULATIVE MASTER INTEGRATION (Non-negotiable)

Always work from the latest approved Master. Never replace it with a shorter reconstruction.

After changing anything:
1. Merge changes into the correct chapter.
2. Keep all 31 topic IDs and all 13 chapters.
3. Keep approved SVGs and verified MCQs.
4. Merge new questions without duplicates.
5. Update concept targets and internal links.
6. Keep navigation and always-visible controls working.
7. Confirm other topics are unchanged unless intentionally edited.
8. Update a version number and a short changelog comment at the top of the HTML.

---

## 17. QUALITY ASSURANCE

### Academic
Concepts covered (ledger), definitions verified, formulas checked, numericals recalculated, graphs checked, repetition removed, MCQ duplicates reviewed, answer keys and explanations checked, current facts dated.

### Technical
Topic/chapter mapping; M1–M4 behaviour; topic numbers visible and opening the right section; Q, Theme, ↑, ↓ working and always visible; MCQ options, scoring, reset; Show Concept and ↩ Back to Question (same chapter and cross-chapter); formula SVGs rendering; dark-mode contrast; search/bookmarks/progress/revision mode (if added); localStorage failure handled; no horizontal overflow; no JavaScript console errors; print preview.

Test at desktop width and at **390px and 320px**.

**Honesty rule:** only report tests that were actually run. If you could not run a browser in this session, say exactly which checks were done by code review only.

---

## 18. DELIVERABLES (after each substantial update)

**A. Updated cumulative Master HTML** (31 topics, 13 chapters, full right-side navigation).

**B. Standalone topic/chapter file**, when useful.

**C. Short audit report:**
- Topics updated and chapter affected
- Gaps found and filled (including Prerequisite blocks)
- Concepts added or corrected
- Source material retained
- Tables removed, merged or kept
- SVGs kept or added; formula accuracy findings
- MCQ counts before → after; duplicates removed; corrected and unresolved questions
- Navigation status; QA results (and how tested)
- Remaining gaps

**D. Status table per topic**, keeping these separate:
| Present in Master | Syllabus Content Complete | MCQ Coverage Complete | Independently Verified | Technical Regression Passed |

Never present a partly verified topic as complete.

---

## 19. COMMANDS

| I say | You do |
|---|---|
| **Start** | Begin the highest-priority unfinished work. |
| **Next / Continue** | Continue the next unfinished topic or audit item, based on actual Master status (or the remaining part of a split reply). |
| **Topic N** | Audit and improve original Topic N within its chapter. |
| **Chapter N** | Audit and develop the whole integrated chapter. |
| **Pura topic ek sath banao** | Finish the whole topic in one coherent update: concepts, diagrams, numericals, MCQs, integration. |
| **Pura chapter ek sath banao** | Finish all topics of the chapter together (split only at topic boundaries if output size forces it). |
| **Audit** | Run Phase 1 + QA only and report; no rebuilding. |
| **Rebuild** | Rebuild the selected topic/chapter, still following all preservation rules. |
| **Demo** | Build one complete topic as a design/quality sample before bulk work. |
| **Final audit** | Review all 31 topics and the full question bank; report verified material, corrections and open gaps. |
| **Final Master** | Deliver the latest cumulative HTML after regression checks. |

---

## 20. FINAL NON-NEGOTIABLE RULES

1. Statistics is excluded.
2. Keep all 31 topic identifiers, organised into 13 chapters. The official syllabus text (§2.0) is the final authority.
3. Topic numbers appear inside M1–M4 navigation, between module labels.
4. Q, Theme, ↑, ↓ always visible on the right.
5. Keep the approved UI; new features must fit inside it.
6. Concepts only — no filler.
7. Detect and fill gaps; add Prerequisite blocks; interlink with Needs/Used in.
8. Explain each shared concept once (ownership map); elsewhere remind + link.
9. Roman Hinglish notes; English MCQ questions and options; Hinglish explanations.
10. Research from NCERT and official sources; date every current figure; never invent data.
11. Preserve detailed existing content; never replace it with summaries.
12. Formulas and diagrams as verified, offline, dark-mode-safe SVG.
13. Verify every answer key and numerical independently.
14. Show Concept ↔ ↩ Back to Question must work everywhere.
15. One cumulative offline Master HTML, versioned.
16. Report only the tests and verification actually done.
17. Strengthen weak topics rather than just increasing counts.
18. Always deliver the updated HTML when implementation is requested.

## FINAL EXECUTION COMMAND

Inspect latest Master → Check source files → Build Coverage Ledger → Research NCERT/official sources → Detect gaps and prerequisites → Apply concept ownership → Remove duplication → Write concept-only content → Verify formulas → Build SVGs → Write and audit MCQs → Wire Show Concept / Back to Question → Merge into the 13-chapter Master → Test all 31 topic links and right-side controls → Run regression at desktop, 390px and 320px → Deliver updated HTML + audit report + status table.

**The goal is not just 31 visible topics. Every topic must be completely explained, accurate, properly tested by MCQs and fully working inside the Master, so a student understands it without confusion.**
