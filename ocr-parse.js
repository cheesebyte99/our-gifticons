/* 우리 기프티콘 — 기프티콘 이미지에서 읽은 글자(OCR 결과)로 이름·브랜드·유효기간을 골라내는 규칙.
 * 화면(index.html)과 시험(test/ocr-parse.test.js)이 같은 파일을 쓴다.
 *
 * GiftOcr.parse(lines, imageHeight, today)
 *   lines: [{ text, top, height, conf? }]  — 한 줄의 글자, 위쪽 y, 글자 높이(픽셀), 읽은 확신도(0~100)
 *   today: 'YYYY-MM-DD' (없으면 오늘)
 *   → { name, brand, expiry }  (못 찾은 칸은 빈 문자열)
 */
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else root.GiftOcr = factory();
})(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  /* 자주 보는 교환처 — 글자 안에서 찾으면 브랜드로 쓴다 (앞쪽이 먼저) */
  const BRANDS = [
    '스타벅스', '투썸플레이스', '투썸', '메가MGC커피', '메가커피', '컴포즈커피', '이디야', '빽다방', '할리스', '폴바셋',
    '커피빈', '파스쿠찌', '엔제리너스', '탐앤탐스', '블루보틀', '더벤티', '매머드커피', '공차', '설빙', '던킨',
    '크리스피크림', '배스킨라빈스', '베스킨라빈스', '파리바게뜨', '뚜레쥬르', '성심당', '노티드',
    'BHC', 'BBQ', '교촌치킨', '교촌', '굽네치킨', '굽네', '처갓집', '네네치킨', '푸라닭', '자담치킨', '60계',
    '버거킹', '맥도날드', '롯데리아', '맘스터치', 'KFC', '쉐이크쉑', '노브랜드버거', '프랭크버거',
    '도미노피자', '피자헛', '파파존스', '미스터피자', '피자스쿨', '7번가피자', '써브웨이', '서브웨이',
    'GS25', 'CU', '세븐일레븐', '이마트24', '이마트', '홈플러스', '롯데마트', '올리브영', '다이소',
    '신세계', '롯데백화점', '현대백화점', 'CGV', '롯데시네마', '메가박스', '배달의민족', '요기요', '쿠팡이츠',
    '아웃백', '빕스', '애슐리', '본죽', '본도시락', '한솥', '이삭토스트', '에그드랍', '역전우동',
    '설록', '오설록', '나뚜루', '하겐다즈', '아티제', '카카오페이', '네이버페이', '컬쳐랜드', '해피머니', '구글플레이'
  ];

  /* 이름 후보에서 뺄 말 (안내 문구·라벨) */
  const STOP = /유효|기간|기한|만료|교환|사용처|주문|번호|바코드|쿠폰|선물|카카오|kakao|gift|매장|직원|제시|발행|잔액|문의|고객|센터|http|www|\.com|일시|수량|구매|결제|받는|보낸|메시지|확인|유의|안내|취소|환불|연장|다운로드|저장|캡처|이용|가능|불가|사용|까지|금액권 잔액|pin|PIN/i;

  function pad(n) { return (n < 10 ? '0' : '') + n; }

  function todayKey() {
    const d = new Date();
    return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate());
  }

  function validDate(y, m, d) {
    if (m < 1 || m > 12 || d < 1 || d > 31) return '';
    const t = new Date(Date.UTC(y, m - 1, d));
    if (t.getUTCMonth() !== m - 1) return '';
    return y + '-' + pad(m) + '-' + pad(d);
  }

  /* OCR 이 숫자를 글자로 잘못 읽은 것 바로잡기 (숫자 옆의 O·l·I 등) */
  function fixDigits(text) {
    return String(text || '')
      .replace(/[０-９]/g, (c) => String.fromCharCode(c.charCodeAt(0) - 0xfee0))
      .replace(/(?<=\d)[Oo](?=[\d.\-/년월일\s])|(?<=[\d.\-/]\s?)[Oo](?=\d)/g, '0')
      .replace(/(?<=\d)[lI|!](?=[\d.\-/년월일\s])|(?<=[\d.\-/]\s?)[lI|!](?=\d)/g, '1')
      .replace(/(?<=\d)[Ss](?=\d)/g, '5');
  }

  /* 한 줄에서 날짜들 찾기 → ['2026-12-31', …] */
  function datesIn(text, allowShortYear) {
    const s = fixDigits(text);
    const out = [];
    const full = /(20\d{2})\s*[.\-/년]\s*(\d{1,2})\s*[.\-/월]\s*(\d{1,2})(?!\d)/g;
    let m;
    while ((m = full.exec(s))) {
      const k = validDate(+m[1], +m[2], +m[3]);
      if (k) out.push(k);
    }
    if (!out.length && allowShortYear) {
      const short = /(?<!\d)(\d{2})\s*[.\-/]\s*(\d{1,2})\s*[.\-/]\s*(\d{1,2})(?!\d)/g;
      while ((m = short.exec(s))) {
        const k = validDate(2000 + +m[1], +m[2], +m[3]);
        if (k) out.push(k);
      }
    }
    return out;
  }

  const EXPIRY_HINT = /유효|기한|기간|까지|만료|~|∼|〜/;

  function findExpiry(lines, today) {
    const hinted = [];
    lines.forEach((l, i) => {
      if (!EXPIRY_HINT.test(l.text)) return;
      hinted.push(...datesIn(l.text, true));
      // 「유효기간」 아래 줄에 날짜만 따로 있는 화면
      if (/유효|기한|기간/.test(l.text) && !datesIn(l.text, true).length && lines[i + 1]) {
        hinted.push(...datesIn(lines[i + 1].text, true));
      }
    });
    let pool = hinted;
    if (!pool.length) {
      // 안내 문구가 안 읽혔으면 글 전체에서 그럴듯한 날짜(1년 전 ~ 6년 뒤)
      const lo = (+today.slice(0, 4) - 1) + today.slice(4);
      const hi = (+today.slice(0, 4) + 6) + today.slice(4);
      lines.forEach((l) => pool.push(...datesIn(l.text, false)));
      pool = pool.filter((k) => k >= lo && k <= hi);
    }
    if (!pool.length) return '';
    return pool.sort()[pool.length - 1]; // 「2026.01.01 ~ 2026.12.31」이면 마지막 날
  }

  function compact(s) {
    return String(s || '').replace(/\s+/g, '').toLowerCase();
  }

  function cleanText(s) {
    return String(s || '')
      .replace(/[|_=<>«»“”"`'^*#@{}[\]\\]/g, ' ')
      .replace(/^[\s:：.,·•\-–—~]+|[\s:：.,·•\-–—~]+$/g, '')
      .replace(/\s{2,}/g, ' ')
      .trim();
  }

  /* 「교환처 스타벅스」처럼 라벨 뒤의 글자 (같은 줄에 없으면 다음 줄) */
  function afterLabel(lines, re) {
    for (let i = 0; i < lines.length; i++) {
      const m = re.exec(lines[i].text);
      if (!m) continue;
      const rest = cleanText(lines[i].text.slice(m.index + m[0].length));
      if (rest) return rest;
      if (lines[i + 1]) return cleanText(lines[i + 1].text);
    }
    return '';
  }

  function knownBrand(text) {
    const c = compact(text);
    for (const b of BRANDS) {
      if (c.indexOf(compact(b)) !== -1) return b;
    }
    return '';
  }

  function findBrand(lines) {
    const labeled = afterLabel(lines, /(교?환처|사용처|브랜드)\s*[:：]?/);
    if (labeled) return knownBrand(labeled) || labeled.slice(0, 30);
    return knownBrand(lines.map((l) => l.text).join(' '));
  }

  function letters(s) {
    const m = String(s).match(/[가-힣A-Za-z]/g);
    return m ? m.length : 0;
  }

  function findName(lines, imageHeight, brand) {
    const labeled = afterLabel(lines, /(상품명|상품|메뉴명)\s*[:：]/);
    if (labeled && letters(labeled) >= 2) return labeled.slice(0, 60);

    const heights = lines.map((l) => l.height || 0).filter((h) => h > 0).sort((a, b) => a - b);
    const median = heights.length ? heights[Math.floor(heights.length / 2)] : 1;
    const H = imageHeight || Math.max(1, ...lines.map((l) => (l.top || 0) + (l.height || 0)));
    let best = null;
    for (const l of lines) {
      const text = cleanText(l.text);
      if (l.conf >= 0 && l.conf < 60) continue; // 그림·무늬를 글자로 잘못 읽은 줄
      if (letters(text) < 2 || text.length > 50) continue;
      if (STOP.test(text)) continue;
      const digits = (text.match(/\d/g) || []).length;
      if (digits > text.length * 0.5) continue; // 바코드 숫자·주문번호
      if (/[가-힣]/.test(text) === false && letters(text) < 4) continue; // 영어 부스러기
      const onlyBrand = brand && compact(text) === compact(brand);
      const ratio = (l.height || median) / median;
      if (ratio > 3 && letters(text) < 5) continue; // 바코드·그림을 커다란 글자 몇 개로 잘못 읽은 것
      let score = Math.min(ratio, 3); // 큰 글자일수록
      const y = (l.top || 0) / H;
      if (y < 0.65) score += 0.6; // 상품 이름은 대개 위쪽
      if (y < 0.08) score -= 0.4; // 맨 위 상태바·로고
      if (/[가-힣]/.test(text)) score += 0.5;
      if (onlyBrand) score -= 1.2;
      if (!best || score > best.score) best = { text, score };
    }
    return best ? best.text.slice(0, 60) : '';
  }

  function parse(lines, imageHeight, today) {
    const list = (lines || [])
      .map((l) => ({
        text: String((l && l.text) || '').replace(/\s+/g, ' ').trim(),
        top: +(l && l.top) || 0,
        height: +(l && l.height) || 0,
        conf: l && typeof l.conf === 'number' ? l.conf : -1
      }))
      .filter((l) => l.text);
    const day = /^\d{4}-\d{2}-\d{2}$/.test(today || '') ? today : todayKey();
    const brand = findBrand(list);
    let name = findName(list, imageHeight, brand);
    if (!name && brand) name = brand;
    return { name, brand, expiry: findExpiry(list, day) };
  }

  return { parse, datesIn, fixDigits, BRANDS };
});
