import { css } from "@emotion/css";
import { Fragment } from "react";
import { useNavigate } from "react-router-dom";
import { createMemo } from "react-use";
import { Bar, BarChart, LabelList, XAxis, YAxis } from "recharts";

import type { ProductTeam } from "../../constants";
import { TeamOwnershipType } from "../../constants";
import { RECTANGLE_HOVER } from "./styles";

// NOTE 16 Nov 2022 (Johannes Moskvil): BarChart data must be memoized for LabelList to render correctly with animations
const useMemoTeamMembersData = createMemo(formatData);

export function TeamTypeChart({ teams }: { teams: ProductTeam[] }) {
  const navigate = useNavigate();

  const memoizedData = useMemoTeamMembersData(teams);

  if (memoizedData.length === 0) {
    return <></>;
  }

  return (
    <Fragment>
      <h2>Andel team per eierskapstype</h2>
      <div
        className={css`
          background: #e6f1f8;
          padding: 2rem;
          width: max-content;
          margin-bottom: 2rem;
          ${RECTANGLE_HOVER}
        `}
      >
        <BarChart barSize={25} data={memoizedData} height={300} layout="vertical" margin={{ right: 40 }} width={600}>
          <Bar
            dataKey="numberOfTypes"
            fill="#005077"
            onClick={(event) => {
              navigate(`/teams/filter?teamOwnershipType=${event.ownershipType}&filterName=${event.text}`);
            }}
            radius={3}
            width={30}
          >
            <LabelList dataKey="numberOfTypes" position="right" />
          </Bar>
          <XAxis hide type="number" />
          <YAxis axisLine={false} dataKey="name" tickLine={false} type="category" width={200} />
        </BarChart>
      </div>
    </Fragment>
  );
}

function formatData(teams: ProductTeam[]) {
  return [
    formatDataRow("Tverrfaglige produktteam", teams, TeamOwnershipType.PRODUCT),
    formatDataRow("IT-team", teams, TeamOwnershipType.IT),
    formatDataRow("Prosjektteam", teams, TeamOwnershipType.PROJECT),
    formatDataRow("Forvaltningsteam", teams, TeamOwnershipType.ADMINISTRATION),
    formatDataRow("Annet", teams, TeamOwnershipType.OTHER),
    formatDataRow("Ukjent", teams, TeamOwnershipType.UNKNOWN),
  ];
}

function formatDataRow(text: string, teams: ProductTeam[], ownershipType: TeamOwnershipType) {
  const teamTypes = teams.map((team) => {
    return team.teamOwnershipType ?? TeamOwnershipType.UNKNOWN;
  });

  const typesInSegment = teamTypes.filter((n) => n === ownershipType);
  const numberOfTypes = typesInSegment.length;

  const percentage = Math.round((typesInSegment.length / teamTypes.length) * 100);

  return {
    name: `${text} (${percentage}%)`,
    text,
    ownershipType,
    numberOfTypes,
  };
}
