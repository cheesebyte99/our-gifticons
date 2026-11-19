'use strict';
/* 이미지 글자 읽기 규칙 시험: node --test test/ */
const test = require('node:test');
const assert = require('node:assert/strict');
const G = require('../ocr-parse.js');

const L = (text, top, height, conf) => ({ text, top, height, conf: conf === undefined ? 90 : conf });
const TODAY = '2026-11-19';

test('카카오톡 선물하기 모양 (바코드를 글자로 잘못 읽은 줄은 빼기)', () => {
  const r = G.parse([
    L('스타벅스', 461, 18), L('카페 아메리카노 T 2잔', 497, 31, 79), L('삐삐', 556, 144, 75),
    L('2x                     스타벅스', 749, 19, 74), L('?효기간          2027년 01월 15일', 804, 19, 86), L('주문번호     1234567890', 859, 18)
  ], 1900, TODAY);
  assert.deepEqual(r, { name: '카페 아메리카노 T 2잔', brand: '스타벅스', expiry: '2027-01-15' });
});

test('[브랜드] 이름 · 사용기한 ~ 까지', () => {
  const r = G.parse([
    L('[투썸플레이스] 스트로베리 초콜릿 생크림', 300, 45), L('쿠폰번호 9876-5432-1098', 500, 30),
    L('사용기한 : 2026.12.31 까지', 560, 30), L('본 쿠폰은 매장에서 직원에게 제시해 주세요.', 620, 22)
  ], 800, TODAY);
  assert.equal(r.brand, '투썸플레이스');
  assert.equal(r.name, '투썸플레이스 스트로베리 초콜릿 생크림');
  assert.equal(r.expiry, '2026-12-31');
});

test('기간이 「시작 ~ 끝」이면 끝나는 날', () => {
  const r = G.parse([L('모바일상품권 1만원권', 60, 52), L('유효기간 2026.11.19 ~ 2027.11.18', 300, 30)], 500, TODAY);
  assert.equal(r.expiry, '2027-11-18');
  assert.equal(r.name, '모바일상품권 1만원권');
});

test('「유효기간」 아래 줄에 날짜', () => {
  const r = G.parse([L('아이스크림 케이크', 100, 40), L('유효기간', 300, 20), L('2027. 3. 5', 330, 20)], 500, TODAY);
  assert.equal(r.expiry, '2027-03-05');
});

test('교환처 라벨, 두 자리 연도, 숫자 오독 바로잡기', () => {
  const r = G.parse([L('교환처 : 배스킨라빈스', 300, 20), L('파인트 아이스크림', 100, 36), L('유효기간 27.O1.l5', 340, 20)], 500, TODAY);
  assert.equal(r.brand, '배스킨라빈스');
  assert.equal(r.expiry, '2027-01-15');
  assert.equal(r.name, '파인트 아이스크림');
});

test('안내 문구가 없으면 그럴듯한 날짜 중 가장 늦은 날 (주문일·먼 옛날은 빼기)', () => {
  const r = G.parse([L('치킨 한 마리', 100, 40), L('2026-11-01', 300, 20), L('2027-05-01', 330, 20), L('1999-01-01', 360, 20)], 500, TODAY);
  assert.equal(r.expiry, '2027-05-01');
});

test('없는 날짜는 버리기', () => {
  assert.deepEqual(G.datesIn('유효기간 2026.02.30', true), []);
  assert.deepEqual(G.datesIn('2026년 12월 31일까지', false), ['2026-12-31']);
});

test('확신도가 낮은 줄·숫자 줄·안내 문구는 이름이 되지 않기', () => {
  const r = G.parse([L('뷁뷁 뛟', 50, 60, 30), L('1234 5678 9012', 200, 40), L('유효기간 2026.12.31', 260, 20), L('불고기 버거 세트', 120, 30)], 400, TODAY);
  assert.equal(r.name, '불고기 버거 세트');
});

test('아무것도 못 읽으면 빈칸', () => {
  assert.deepEqual(G.parse([], 0, TODAY), { name: '', brand: '', expiry: '' });
  assert.deepEqual(G.parse([L('', 0, 0)], 0, TODAY), { name: '', brand: '', expiry: '' });
});
