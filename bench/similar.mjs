// the trigrams' cosine between needs' and offers' subjects: where the market's floor goes
const { tools } = await import("./tools.js");
const pairs = [["сантехник", ["сантехнику", "английский онлайн", "ремонт квартир", "краны и трубы", "фото свадеб"]],
  ["репетитор по английскому", ["английский онлайн", "уроки английского", "сантехнику", "фото свадеб"]],
  ["фотограф на свадьбу", ["фото свадеб", "снимаю свадьбы", "сантехнику", "английский онлайн"]],
  ["электрик", ["электрику", "электромонтаж", "ремонт квартир", "сантехнику"]]];
for (const [need, offers] of pairs) for (const o of offers) console.log(tools.trigramSimilarity(need, o).toFixed(3), need, "|", o);
